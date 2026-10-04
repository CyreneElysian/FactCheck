"""
第 4 批新增的两条健壮性契约的回归测试。

1. `OpenAICompatibleLLM.chat(deadline=...)`：重试必须按**剩余预算**重新收紧超时，
   且预算耗尽时不再重试。修复前的实现只在进入方法前算一次剩余时间，
   每次重试都给一份完整超时，最坏把单次 /detect 拉到约 3 倍预算，
   击穿 Java 侧 90s 读超时（出现"Java 已判失败、Agent 仍在跑"的状态不一致）。

2. `DedupService._reconcile()`：embedding **整体**不可用时必须整体放弃并完整保留元数据。
   修复前会逐条失败、逐条 pop 元数据，把整个去重知识库清空后落盘 ——
   这是本服务唯一可能造成不可恢复数据丢失的路径。

全部离线：不加载真实模型、不发任何 HTTP。
"""
import json
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from app.services.dedup_service import DedupService
from app.services.llm import OpenAICompatibleLLM

from tests._helpers import FakeEmbeddingService, OfflineTestCase


class TestLLMDeadlineBudget(OfflineTestCase):
    """重试不得超过整体预算"""

    def _llm(self, retry=2):
        return OpenAICompatibleLLM(
            base_url="http://127.0.0.1:9", api_key="k", model="m",
            timeout=60, temperature=0.1, retry=retry,
        )

    def test_retry_timeout_is_clamped_to_remaining_budget(self):
        """每次重试的 timeout 都必须小于等于剩余预算，而不是沿用首次的完整超时"""
        llm = self._llm(retry=2)
        seen_timeouts = []

        def fake_post(url, json=None, headers=None, timeout=None):
            seen_timeouts.append(timeout)
            raise RuntimeError("simulated llm failure")

        deadline = time.monotonic() + 5
        with patch("app.services.llm.requests.post", side_effect=fake_post):
            with self.assertRaises(RuntimeError):
                llm.chat([{"role": "user", "content": "x"}], timeout=60, deadline=deadline)

        self.assertTrue(seen_timeouts, "at least one attempt expected")
        for timeout in seen_timeouts:
            self.assertLessEqual(
                timeout, 6,
                "timeout=%s exceeded remaining budget; retry would break the budget" % timeout)

    def test_no_retry_when_budget_already_exhausted(self):
        """预算已耗尽时一次都不该发请求"""
        llm = self._llm(retry=2)
        calls = []

        def fake_post(url, json=None, headers=None, timeout=None):
            calls.append(timeout)
            raise RuntimeError("must not be called")

        deadline = time.monotonic() - 1
        with patch("app.services.llm.requests.post", side_effect=fake_post):
            with self.assertRaises(RuntimeError):
                llm.chat([{"role": "user", "content": "x"}], timeout=60, deadline=deadline)

        self.assertEqual(calls, [], "no request should be sent after the budget is gone")

    def test_retry_stops_when_backoff_exceeds_budget(self):
        """退避时间已超出剩余预算时不再重试（避免白等）"""
        llm = self._llm(retry=2)
        calls = []

        def fake_post(url, json=None, headers=None, timeout=None):
            calls.append(timeout)
            raise RuntimeError("simulated llm failure")

        deadline = time.monotonic() + 0.5
        with patch("app.services.llm.requests.post", side_effect=fake_post):
            with self.assertRaises(RuntimeError):
                llm.chat([{"role": "user", "content": "x"}], timeout=60, deadline=deadline)

        self.assertEqual(len(calls), 1, "expected exactly one attempt, got %d" % len(calls))

    def test_without_deadline_behaviour_is_unchanged(self):
        """不传 deadline 时保持原有语义（最多 retry+1 次）"""
        llm = self._llm(retry=2)
        calls = []

        def fake_post(url, json=None, headers=None, timeout=None):
            calls.append(timeout)
            raise RuntimeError("simulated llm failure")

        with patch("app.services.llm.requests.post", side_effect=fake_post), \
                patch("app.services.llm.time.sleep", return_value=None):
            with self.assertRaises(RuntimeError):
                llm.chat([{"role": "user", "content": "x"}], timeout=7)

        self.assertEqual(len(calls), 3, "retry=2 should mean 3 attempts")
        self.assertTrue(all(t == 7 for t in calls), "timeout should be passed through")


class _RecordingEmbedding(FakeEmbeddingService):
    """探针可过、但指定文本一定失败的 embedding（模拟单条文本异常）"""

    def __init__(self, fail_substring, dim=64):
        super().__init__(dim=dim)
        self.fail_substring = fail_substring

    def embed(self, text):
        if self.fail_substring in text:
            raise RuntimeError("this text cannot be embedded")
        return super().embed(text)


class TestDedupReconcileKeepsMetadataOnEmbeddingOutage(OfflineTestCase):
    """embedding 整体不可用时，绝不能把元数据删光"""

    def _make_service(self, data_dir, embedding):
        return DedupService(
            embedding_service=embedding,
            data_dir=Path(data_dir),
            threshold=0.92,
        )

    def test_embedding_outage_keeps_all_metadata(self):
        with tempfile.TemporaryDirectory() as tmp:
            good = FakeEmbeddingService()
            service = self._make_service(tmp, good)
            service.add(1, "first indexed claim text")
            service.add(2, "second indexed claim text")
            self.assertEqual(len(service._meta), 2)

            # 模拟"重启时 embedding 不可用"：磁盘上只剩元数据（索引文件被删掉），
            # 于是加载期进入补齐分支，而每一次 embed() 都失败。
            if service.index_path.exists():
                service.index_path.unlink()

            broken = FakeEmbeddingService(raise_error=RuntimeError("model load failed offline"))
            revived = self._make_service(tmp, broken)

            # 关键断言：元数据必须完整保留，不能被逐条 pop 掉
            self.assertEqual(
                len(revived._meta), 2,
                "embedding outage must skip reconcile entirely instead of dropping metadata")
            self.assertIn(1, revived._meta)
            self.assertIn(2, revived._meta)

    def test_single_bad_record_is_dropped_and_persisted(self):
        """只有个别条目无法补齐时才丢弃，并且**必须落盘**（否则每次启动重复失败）"""
        unique = "UNIQUEWORD"
        with tempfile.TemporaryDirectory() as tmp:
            good = FakeEmbeddingService()
            service = self._make_service(tmp, good)
            service.add(1, "normal claim one")
            service.add(2, "bad claim %s" % unique)
            self.assertEqual(len(service._meta), 2)

            if service.index_path.exists():
                service.index_path.unlink()

            revived = self._make_service(tmp, _RecordingEmbedding(unique))
            self.assertEqual(len(revived._meta), 1, "the unrepairable entry should be dropped")
            self.assertIn(1, revived._meta)
            self.assertNotIn(2, revived._meta)

            # 落盘复核：磁盘上的元数据也必须只剩 1 条（不能只在内存里改）
            with open(revived.meta_path, encoding="utf-8") as handle:
                saved = json.load(handle)
            self.assertEqual(len(saved), 1,
                             "dropping must be persisted, otherwise it repeats on every boot")
            self.assertIn("1", saved)


if __name__ == "__main__":
    unittest.main()
