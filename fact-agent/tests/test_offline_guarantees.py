"""
离线约束自检：确认测试确实没有联网、没有加载真实模型、没有碰生产 data/ 目录

这一组用例的价值在于：任何"不小心把假对象换成真对象"的改动都会在这里暴露。
"""
import socket
import unittest
from unittest.mock import MagicMock, patch

import requests

from app.config import settings
from app.services import detect_service as detect_service_module
from app.services.detect_service import DetectService
from app.services.search import SearchResult, get_search_tool

from tests import _helpers
from tests._helpers import FakeLLM, FakeSearchTool, OfflineTestCase, json_text


def _rmtree(path):
    """cleanup 用的目录删除（不存在时静默通过）"""
    import shutil
    shutil.rmtree(path, ignore_errors=True)


class TestOfflineGuarantees(OfflineTestCase):

    def test_socket_guard_blocks_external_connections(self):
        """锁定行为：测试期 socket 熔断生效，任何对外连接尝试都会失败"""
        guard = _helpers._NETWORK_GUARD
        self.assertIsNotNone(guard._original, "socket 熔断必须已安装")
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.addCleanup(sock.close)
        with self.assertRaises(AssertionError):
            sock.connect(("203.0.113.1", 80))
        self.assertIn("203.0.113.1", guard.blocked_hosts)

    def test_detect_with_fakes_makes_no_http_request(self):
        """锁定行为：整条检测链路在假依赖下不发出任何 HTTP 请求"""
        llm = FakeLLM(["查询词", json_text(can_judge=True, verdict="TRUE",
                                          confidence=0.9, evidence=[])])
        service = DetectService(FakeSearchTool([SearchResult(
            title="标题", url="https://www.xinhuanet.com/a", snippet="摘要")]), llm)

        with patch.object(requests.sessions.Session, "request",
                          new=MagicMock(side_effect=AssertionError("测试中不允许发出 HTTP 请求"))):
            self.patch_setting("SEARCH_FETCH_TOP", 3)  # 即使打开抓取也不能出网
            with patch.object(DetectService, "_fetch_page_text",
                              new=MagicMock(return_value="假正文")):
                resp = service.detect("武汉又出现新型病毒了")

        self.assertFalse(resp.error)
        self.assertTrue(resp.can_judge)

    def test_detect_without_fetch_stub_would_hit_http(self):
        """锁定行为（反向验证）：不替换 _fetch_page_text 时真的会走 requests ——
        这正是所有用例都要么关掉 SEARCH_FETCH_TOP、要么打桩该方法的原因"""
        service = DetectService(FakeSearchTool([SearchResult(
            title="标题", url="https://example.com/a", snippet="摘要")]),
            FakeLLM(["查询词"]))
        self.patch_setting("SEARCH_FETCH_TOP", 1)

        with patch.object(requests, "get",
                          new=MagicMock(side_effect=RuntimeError("这里本应出网"))) as mocked_get:
            results = service._enrich_results(
                [SearchResult(title="t", url="https://example.com/a", snippet="s")], 1e9)

        self.assertTrue(mocked_get.called, "确认抓取路径确实会调用 requests.get")
        self.assertEqual("", results[0].content, "抓取失败时 content 保持为空，回退到 snippet")

    def test_settings_fetch_top_restored_after_test(self):
        """锁定行为：基类把 SEARCH_FETCH_TOP 临时置 0，保证默认不出网"""
        self.assertEqual(0, settings.SEARCH_FETCH_TOP)

    def test_real_embedding_model_is_never_constructed(self):
        """锁定行为：测试不会加载真实 bge 模型（EmbeddingService._load 不应被调用）"""
        from app.services.embedding import EmbeddingService

        with patch.object(EmbeddingService, "_load",
                          new=MagicMock(side_effect=AssertionError("测试不应加载真实模型"))):
            fake = _helpers.FakeEmbeddingService()
            vec = fake.embed("一段文本")
        self.assertEqual(1, len(vec.shape))
        self.assertEqual(_helpers._FAKE_DIM, vec.shape[0])

    def test_production_data_dir_is_untouched(self):
        """锁定行为：测试不读写生产 data/ 目录——DedupService 一律显式传入 tempfile 目录"""
        import os
        import tempfile
        from pathlib import Path

        production_dir = settings.DATA_DIR.resolve()
        self.assertEqual("data", production_dir.name,
                         "生产数据目录应仍解析为 fact-agent/data")
        self.assertNotIn(str(Path(tempfile.gettempdir()).resolve()).lower(),
                         str(production_dir).lower(),
                         "生产数据目录不应位于临时目录，否则本断言无意义")

        # DedupService 的构造在任何用例里都必须由测试显式给出 tempfile 目录
        from app.services.dedup_service import DedupService
        temp_dir = Path(tempfile.mkdtemp(prefix="fact-agent-guard-"))
        self.addCleanup(_rmtree, temp_dir)
        service = DedupService(_helpers.FakeEmbeddingService(), temp_dir)
        self.assertEqual(temp_dir, service.data_dir)
        self.assertNotEqual(production_dir, service.data_dir.resolve())
        self.assertEqual([], sorted(p.name for p in temp_dir.iterdir()),
                         "构造服务本身不应写盘（只有 add 才落盘）")

        # 生产 data/ 目录里不应出现测试写入的 .tmp 残留
        leftovers = [n for n in os.listdir(production_dir) if n.endswith(".tmp")] \
            if production_dir.exists() else []
        self.assertEqual([], leftovers, "生产 data/ 目录不应有测试留下的 .tmp 文件")

    def test_search_tool_factory_in_tests_never_uses_real_provider(self):
        """锁定行为：测试里只用 dummy 或 FakeSearchTool，不用真实联网搜索源"""
        self.assertIsInstance(FakeSearchTool([]), detect_service_module.SearchTool)
        self.assertEqual([], get_search_tool("dummy", "").search("q"))


if __name__ == "__main__":
    unittest.main()
