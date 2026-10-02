"""
app/services/dedup_service.py（DedupService）行为锁定测试

全部离线：
- 用 FakeEmbeddingService 代替真实 bge 模型（确定性归一化向量，不加载模型）；
- 用 tempfile.mkdtemp() 作为 data_dir，绝不接触生产 fact-agent/data/ 目录
  （那里有用真实数据构建的 FAISS 索引）。
"""
import json
import os
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import faiss
import numpy as np

from app.services.dedup_service import DedupService

from tests._helpers import FakeEmbeddingService, OfflineTestCase

TEXT_A = "武汉又出现新型病毒了，大家不要出门"          # ≥ DEDUP_MIN_TEXT_LENGTH
TEXT_B = "明天全市停水停电，请提前储水"


class DedupServiceTestCase(OfflineTestCase):
    """每个用例独立的临时数据目录"""

    def setUp(self):
        super().setUp()
        self.data_dir = Path(tempfile.mkdtemp(prefix="fact-agent-dedup-"))
        self.addCleanup(shutil.rmtree, self.data_dir, True)
        self.embedding = FakeEmbeddingService()

    def build_service(self, embedding=None) -> DedupService:
        return DedupService(embedding or self.embedding, self.data_dir,
                            threshold=0.92, min_text_length=8, top_k=1)

    def files(self):
        return {p.name for p in self.data_dir.iterdir()}


class TestDedupPersistence(DedupServiceTestCase):

    def test_add_persists_index_and_meta_without_tmp_leftovers(self):
        """锁定行为：add 两条后索引与元数据都落盘，且目录里没有残留 .tmp（原子写）"""
        service = self.build_service()
        service.add(1, TEXT_A)
        service.add(2, TEXT_B)

        names = self.files()
        self.assertIn("claims.index", names)
        self.assertIn("claims_meta.json", names)
        self.assertEqual([], [n for n in names if n.endswith(".tmp")],
                         "原子写完成后不应残留临时文件")
        self.assertEqual(2, len(service._meta))

        meta = json.loads((self.data_dir / "claims_meta.json").read_text(encoding="utf-8"))
        self.assertEqual({str(1): TEXT_A, str(2): TEXT_B}, meta)

    def test_search_same_text_hits_with_high_score(self):
        """锁定行为：同一文本检索命中，相似度 > 0.99（归一化向量内积≈余弦）"""
        service = self.build_service()
        service.add(1, TEXT_A)
        service.add(2, TEXT_B)

        matched, record_id, score, matched_text = service.search(TEXT_A)
        self.assertTrue(matched)
        self.assertEqual(1, record_id)
        self.assertGreater(score, 0.99)
        self.assertEqual(TEXT_A, matched_text)

    def test_search_different_text_misses(self):
        """锁定行为：不同文本不会误命中（阈值 0.92 生效）"""
        service = self.build_service()
        service.add(1, TEXT_A)

        matched, record_id, score, matched_text = service.search("今天股市大涨，快去买股票")
        self.assertFalse(matched)
        self.assertIsNone(record_id)
        self.assertIsNone(matched_text)
        self.assertLess(score, 0.92)

    def test_short_text_is_not_indexed(self):
        """锁定行为：短于 DEDUP_MIN_TEXT_LENGTH 的文本不进索引（避免噪声向量）"""
        service = self.build_service()
        service.add(1, "太短")
        self.assertEqual({}, service._meta)

    def test_reload_after_restart_keeps_meta(self):
        """锁定行为：重新构造 DedupService（模拟重启）后仍能加载 2 条元数据"""
        first = self.build_service()
        first.add(1, TEXT_A)
        first.add(2, TEXT_B)

        restarted = self.build_service()
        self.assertEqual(2, len(restarted._meta))
        self.assertIsNotNone(restarted._index)
        self.assertEqual(2, restarted._index.ntotal)
        matched, record_id, _, _ = restarted.search(TEXT_B)
        self.assertTrue(matched)
        self.assertEqual(2, record_id)

    def test_embedding_failure_does_not_write_meta(self):
        """锁定行为：embedding 失败时本次不写索引，也不写元数据（避免向量与元数据不一致）"""
        service = self.build_service()
        service.add(1, TEXT_A)
        self.assertEqual(1, len(service._meta))

        broken = self.build_service(FakeEmbeddingService(raise_error=RuntimeError("模型不可用")))
        broken.add(2, TEXT_B)

        self.assertEqual(1, len(broken._meta), "embedding 失败不应写入新元数据")
        matched, _, _, _ = broken.search(TEXT_B)
        self.assertFalse(matched)


class TestDedupAtomicWrite(DedupServiceTestCase):
    """写盘失败时不能破坏已有索引（原子写的真正价值）"""

    def test_index_write_failure_keeps_previous_index_intact(self):
        """锁定行为：写索引中途崩溃时只留下 .tmp 半成品，原 claims.index 字节完全不变

        P5-68 之后写入顺序改为"先元数据、后索引"，因此这里的元数据**会**被更新：
        元数据是事实来源，先落盘才能保证崩溃后可自愈（见下面那条断言与
        TestDedupCrashConsistency.test_missing_vectors_are_recovered_on_load）。
        """
        service = self.build_service()
        service.add(1, TEXT_A)

        index_path = self.data_dir / "claims.index"
        meta_path = self.data_dir / "claims_meta.json"
        good_bytes = index_path.read_bytes()

        def half_write(index, path):
            # 先写一半再抛错，模拟"写索引过程中崩溃"
            Path(path).write_bytes(good_bytes[: max(1, len(good_bytes) // 2)])
            raise RuntimeError("模拟写索引时崩溃")

        with patch("faiss.write_index", new=half_write):
            service._meta[2] = TEXT_B
            service._save()  # 内部吞掉异常，不应向外抛

        self.assertEqual(good_bytes, index_path.read_bytes(),
                         "写盘失败时原索引必须原样保留（os.replace 未执行）")
        self.assertEqual({1: TEXT_A, 2: TEXT_B}, service._meta)
        meta_on_disk = json.loads(meta_path.read_text(encoding="utf-8"))
        self.assertEqual({"1": TEXT_A, "2": TEXT_B}, meta_on_disk,
                         "元数据先落盘：索引失败时它已更新，重启后可据此补齐向量（自愈）")
        self.assertEqual([], [p.name for p in self.data_dir.glob("*.bak")],
                         "写盘失败不应触发隔离备份")
        self.assertTrue(any(n.endswith(".tmp") for n in self.files()),
                        "失败路径会留下 .tmp 半成品——可接受，重点是主文件没被破坏")

        # 关键：重启后能自愈（元数据比索引多一条 → 按元数据补齐向量）
        reloaded = self.build_service()
        self.assertEqual(2, len(reloaded._meta))
        matched, record_id, _, matched_text = reloaded.search(TEXT_B)
        self.assertTrue(matched, "崩溃残留的状态应能在重启时自愈")
        self.assertEqual(2, record_id)
        self.assertEqual(TEXT_B, matched_text)

    def test_save_failure_is_reported_not_silent(self):
        """锁定行为（修复后）：_save() 失败返回 False，add() 抛异常而不是静默报成功

        修复前：add() 写盘失败仍然正常返回并打印"已加入去重索引"，磁盘上什么都没有，
        调用方（Java 的 /index/add）以为索引已更新 → 重启丢数据且无人感知。
        """
        service = self.build_service()
        service.add(1, TEXT_A)

        def always_fail(index, path):
            raise RuntimeError("模拟写索引失败")

        with patch("faiss.write_index", new=always_fail):
            self.assertFalse(service._save(), "_save 失败必须返回 False")
            with self.assertRaises(RuntimeError, msg="add() 必须把持久化失败上报给调用方"):
                service.add(2, TEXT_B)

    def test_save_is_atomic_success_path_has_no_tmp(self):
        """锁定行为：成功路径下 .tmp 一定被 os.replace 消费掉，反复写盘也不累积临时文件"""
        service = self.build_service()
        service.add(1, TEXT_A)
        service.add(2, TEXT_B)
        for _ in range(3):
            service._save()
        self.assertEqual([], [n for n in self.files() if n.endswith(".tmp")])
        self.assertNotEqual(b"", (self.data_dir / "claims.index").read_bytes())


class TestDedupLoadFailure(DedupServiceTestCase):
    def test_corrupt_index_is_quarantined_not_overwritten(self):
        """锁定行为：加载失败时把可疑文件另存为 .bak，原始字节与元数据都不丢，服务仍可继续"""
        garbage = b"NOT-A-FAISS-INDEX\x00\x01\x02garbage-bytes"
        original_meta = json.dumps({"1": TEXT_A, "2": TEXT_B}, ensure_ascii=False)

        (self.data_dir / "claims.index").write_bytes(garbage)
        (self.data_dir / "claims_meta.json").write_text(original_meta, encoding="utf-8")

        service = self.build_service()  # 不应抛异常

        backups = sorted(self.data_dir.glob("*.bak"))
        self.assertTrue(backups, "加载失败必须留下 .bak 备份")
        self.assertTrue(any(n.endswith(".bak") for n in self.files()))

        # 垃圾索引的字节被完整保留到某个 .bak 里
        index_backups = [p for p in backups if p.name.startswith("claims.index")]
        self.assertTrue(index_backups, "索引文件应被备份")
        self.assertEqual(garbage, index_backups[0].read_bytes(),
                         "备份内容必须与原垃圾字节完全一致")

        # 原元数据内容也出现在某个 .bak 里（没有被空内容覆盖）
        meta_backups = [p for p in backups if p.name.startswith("claims_meta.json")]
        self.assertTrue(meta_backups, "元数据文件应被备份")
        self.assertIn(TEXT_A, meta_backups[0].read_text(encoding="utf-8"))

        # 服务仍可继续：以空索引启动，不抛异常
        self.assertEqual({}, service._meta)
        self.assertIsNone(service._index)
        matched, record_id, score, matched_text = service.search(TEXT_A)
        self.assertFalse(matched)
        self.assertIsNone(record_id)
        self.assertEqual(0.0, score)
        self.assertIsNone(matched_text)

    def test_service_usable_after_quarantine(self):
        """锁定行为：隔离损坏文件后仍能正常 add/search（不会因一次坏索引彻底瘫痪）"""
        (self.data_dir / "claims.index").write_bytes(b"broken")
        (self.data_dir / "claims_meta.json").write_text("{}", encoding="utf-8")

        service = self.build_service()
        service.add(7, TEXT_A)

        matched, record_id, score, _ = service.search(TEXT_A)
        self.assertTrue(matched)
        self.assertEqual(7, record_id)
        self.assertGreater(score, 0.99)

    def test_missing_index_starts_empty(self):
        """锁定行为：索引文件不存在时安静地新建（不产生 .bak）"""
        service = self.build_service()
        self.assertEqual({}, service._meta)
        self.assertIsNone(service._index)
        self.assertEqual([], list(self.data_dir.glob("*.bak")))

    def test_corrupt_meta_only_is_quarantined(self):
        """锁定行为：只有元数据损坏（JSON 非法）时同样走隔离路径，不丢原始内容"""
        first = self.build_service()
        first.add(1, TEXT_A)
        (self.data_dir / "claims_meta.json").write_text("{不是合法JSON", encoding="utf-8")

        service = self.build_service()
        self.assertEqual({}, service._meta)
        self.assertIsNone(service._index)
        meta_backups = [p for p in self.data_dir.glob("*.bak")
                        if p.name.startswith("claims_meta.json")]
        self.assertTrue(meta_backups)
        self.assertIn("不是合法JSON", meta_backups[0].read_text(encoding="utf-8"))


class TestDedupThresholdConfig(DedupServiceTestCase):

    def test_threshold_boundary_respected(self):
        """锁定行为：自定义阈值参与判定（阈值调到 1.0 以上则同文本也不命中）"""
        service = DedupService(self.embedding, self.data_dir, threshold=0.5,
                               min_text_length=8, top_k=1)
        service.add(1, TEXT_A)
        matched, _, score, _ = service.search(TEXT_A)
        self.assertTrue(matched)
        self.assertGreater(score, 0.5)

        strict = DedupService(self.embedding, self.data_dir, threshold=1.5,
                              min_text_length=8, top_k=1)
        matched, _, _, _ = strict.search(TEXT_A)
        self.assertFalse(matched, "阈值高于最大相似度时不应命中")


class TestDedupCrashConsistency(DedupServiceTestCase):
    """
    P5-68：写入中途崩溃后"索引向量"与"元数据"不一致时的行为

    原则：**元数据是事实来源，索引是由它派生的向量数据**。
    因此写入顺序必须是"先元数据、后索引"，加载时要能自愈。
    """

    def test_meta_is_written_before_index(self):
        """锁定写入顺序：先 claims_meta.json，再 claims.index"""
        service = self.build_service()
        replaced = []
        real_replace = os.replace

        def spy(src, dst):
            replaced.append(Path(dst).name)
            return real_replace(src, dst)

        with patch("app.services.dedup_service.os.replace", side_effect=spy):
            service.add(1, TEXT_A)

        self.assertEqual(["claims_meta.json", "claims.index"], replaced,
                         "元数据（事实来源）必须先落盘，避免产生无主向量")

    def test_orphan_vectors_are_removed_on_load(self):
        """索引里有向量但元数据里没有（旧版写入顺序的崩溃残留）→ 加载时删除"""
        service = self.build_service()
        service.add(1, TEXT_A)

        # 手工塞一条没有元数据的向量，模拟崩溃残留
        vec = np.expand_dims(self.embedding.embed(TEXT_B), axis=0)
        service._index.add_with_ids(vec, np.array([999], dtype="int64"))
        faiss.write_index(service._index, str(service.index_path))

        reloaded = self.build_service()
        ids = {int(i) for i in faiss.vector_to_array(reloaded._index.id_map)}
        self.assertEqual({1}, ids, "无主向量应被清理")

        matched, record_id, score, matched_text = reloaded.search(TEXT_B)
        self.assertFalse(matched, "无主向量清理后不应再命中")
        self.assertIsNone(matched_text)

    def test_missing_vectors_are_recovered_on_load(self):
        """元数据里有条目但索引里没有向量（先写元数据后崩溃）→ 加载时按元数据补齐"""
        service = self.build_service()
        service.add(1, TEXT_A)

        # 模拟：元数据写了 2 条，索引只成功写入 1 条
        meta = json.loads((self.data_dir / "claims_meta.json").read_text(encoding="utf-8"))
        meta["2"] = TEXT_B
        (self.data_dir / "claims_meta.json").write_text(
            json.dumps(meta, ensure_ascii=False), encoding="utf-8")

        reloaded = self.build_service()
        self.assertEqual(2, len(reloaded._meta))
        matched, record_id, score, matched_text = reloaded.search(TEXT_B)
        self.assertTrue(matched, "缺失的向量应在加载时被补齐，而不是静默丢数据")
        self.assertEqual(2, record_id)
        self.assertEqual(TEXT_B, matched_text)

    def test_meta_without_index_file_is_rebuilt(self):
        """只有元数据、索引文件缺失（首次写盘时索引写失败）→ 按元数据重建，不丢数据"""
        service = self.build_service()
        service.add(1, TEXT_A)
        (self.data_dir / "claims.index").unlink()

        reloaded = self.build_service()
        self.assertEqual(1, len(reloaded._meta), "元数据不能被当成空索引丢掉")
        matched, record_id, score, matched_text = reloaded.search(TEXT_A)
        self.assertTrue(matched, "索引文件缺失时应按元数据重建向量")
        self.assertEqual(TEXT_A, matched_text)

    def test_index_without_meta_is_quarantined(self):
        """只有索引、没有元数据 → 检索到也无法展示文本，按不可用隔离而不是静默继续"""
        service = self.build_service()
        service.add(1, TEXT_A)
        (self.data_dir / "claims_meta.json").unlink()

        reloaded = self.build_service()
        self.assertEqual({}, reloaded._meta)
        self.assertIsNone(reloaded._index)
        self.assertTrue(any(p.name.startswith("claims.index") and p.name.endswith(".bak")
                            for p in self.data_dir.iterdir()),
                        "可疑索引应被另存为 .bak，避免被后续写入覆盖")

    def test_search_never_returns_match_without_text(self):
        """检索时若遇到无元数据的向量，必须跳过（或顺位取下一个），不能返回 matched=True 但无文本"""
        service = self.build_service()
        service.add(1, TEXT_A)

        # 绕过加载期修复，直接在内存里注入无主向量
        vec = np.expand_dims(self.embedding.embed(TEXT_A), axis=0)
        service._index.add_with_ids(vec, np.array([999], dtype="int64"))

        matched, record_id, score, matched_text = service.search(TEXT_A)
        self.assertNotEqual(999, record_id)
        if matched:
            self.assertIsNotNone(matched_text, "命中必须带文本")


if __name__ == "__main__":
    unittest.main()
