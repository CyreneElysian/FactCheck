"""
语义去重服务（小模型）

使用 FAISS IndexFlatIP + Embedding 向量做余弦相似度检索。
索引与元数据持久化到本地 data 目录，启动时自动加载。

持久化安全（重要）：
- 写盘采用"临时文件 + 原子替换"，避免写一半崩溃留下半截索引；
- **先写元数据、后写索引**：元数据是事实来源（recordId -> 原文），索引只是由它派生的向量数据。
  这样即使两步之间崩溃，也只会让索引暂时落后（`_load` 会按元数据补齐），
  而不会产生"索引里有向量、元数据里没有对应条目"的无主数据；
- 加载时做一致性自愈：删除无主向量、按元数据补齐缺失向量、检索时跳过无元数据的向量；
- 内存索引与写盘操作使用同一把可重入锁，避免并发请求交叉写坏文件；
- 加载失败时**不覆盖原文件**，而是把可疑文件另存为 .bak 后再从空索引开始，
  避免"启动即丢历史索引"。

多进程限制：索引是**进程内内存态**，因此本服务必须以单 worker 运行
（uvicorn 不加 --workers，或 --workers 1）。多 worker 会各持一份索引并互相覆盖。
"""
import json
import logging
import os
import threading
import time
from pathlib import Path
from typing import Dict, Optional, Tuple

import numpy as np

from .embedding import EmbeddingService

logger = logging.getLogger(__name__)


class DedupService:
    """语义去重服务"""

    def __init__(self, embedding_service: EmbeddingService, data_dir: Path,
                 threshold: float = 0.92, min_text_length: int = 8, top_k: int = 1):
        self.embedding_service = embedding_service
        self.threshold = threshold
        self.min_text_length = min_text_length
        self.top_k = top_k
        self.data_dir = data_dir
        self.data_dir.mkdir(parents=True, exist_ok=True)

        self.index_path = self.data_dir / "claims.index"
        self.meta_path = self.data_dir / "claims_meta.json"
        self._index = None
        self._meta: Dict[int, str] = {}
        # 索引与写盘共用一把锁（可重入，_save 可能被 add 内部再次调用）
        self._lock = threading.RLock()
        self._load()

    def _load(self):
        """启动时加载已持久化的索引与元数据；失败时保留原文件，不清空覆盖"""
        try:
            has_index = self.index_path.exists()
            has_meta = self.meta_path.exists()
            if not has_index and not has_meta:
                logger.info("去重索引不存在，将新建（data_dir=%s）", self.data_dir)
                return
            if has_index and not has_meta:
                # 只有向量没有文本：检索到也无法展示"相似的历史消息"，按不可用处理
                raise ValueError("索引文件存在但元数据缺失，无法安全使用")

            import faiss
            if has_index:
                self._index = faiss.read_index(str(self.index_path))
            with open(self.meta_path, "r", encoding="utf-8") as f:
                self._meta = {int(k): v for k, v in json.load(f).items()}
            if not has_index:
                logger.warning("元数据存在但索引文件缺失（写入中途崩溃残留），将按元数据重建向量")
            # 修复"索引向量"与"元数据"不一致的残留（崩溃/断电才会出现）
            self._reconcile()
            logger.info("去重索引加载完成，共 %d 条", len(self._meta))
        except Exception as exc:
            logger.error("去重索引加载失败：%s；已将可疑文件另存为 .bak 后重建，不会覆盖原始数据", exc)
            self._index = None
            self._meta = {}
            self._quarantine_files()

    def _ensure_index(self, dim: int):
        """确保内存索引存在（IndexIDMap 包装 IndexFlatIP），不存在则新建"""
        import faiss
        if self._index is None:
            self._index = faiss.IndexIDMap(faiss.IndexFlatIP(dim))
        return self._index

    def _reconcile(self) -> None:
        """
        修复"索引向量"与"元数据"数量不一致的残留状态

        正常情况下 `_save` 会把两个文件写成一致；只有写入过程中崩溃/断电才会不一致。
        这里按"**元数据是事实来源**，索引是由它派生的向量数据"来处理：
        - 索引里多出的向量（没有对应元数据，检索到也没有文本可展示）→ 直接删除；
        - 元数据里多出的条目（向量没写成功）→ 重新生成向量补上；补不上就丢弃该条目并告警。
        """
        if self._index is None and not self._meta:
            return

        index_ids = set()
        if self._index is not None:
            try:
                import faiss
                index_ids = {int(i) for i in faiss.vector_to_array(self._index.id_map)}
            except Exception as exc:
                logger.warning("无法读取索引内的 id 列表，跳过一致性修复：%s", exc)
                return

        meta_ids = set(self._meta.keys())

        orphans = index_ids - meta_ids
        if orphans:
            logger.warning("索引中有 %d 条向量没有元数据（写入中途崩溃残留），已删除：%s",
                           len(orphans), sorted(orphans)[:10])
            try:
                import faiss
                self._index.remove_ids(faiss.IDSelectorBatch(
                    np.array(sorted(orphans), dtype="int64")))
            except Exception as exc:
                logger.error("删除无主向量失败（检索时会跳过它们）：%s", exc)

        missing = meta_ids - index_ids
        if missing:
            logger.warning("元数据中有 %d 条没有对应向量（写入中途崩溃残留），尝试补齐：%s",
                           len(missing), sorted(missing)[:10])
            # 先探活：embedding 整体不可用（模型缺失/离线无法下载）时必须**整体放弃**，
            # 否则下面会逐条失败、逐条 pop 元数据，把整个知识库清空后落盘 ——
            # 这是本服务唯一可能造成不可恢复数据丢失的路径。
            try:
                self.embedding_service.embed("__probe__")
            except Exception as exc:
                logger.error("embedding 服务不可用，本次跳过向量补齐并**完整保留**元数据"
                             "（避免把整个去重索引的元数据清空）：%s", exc)
                return
            recovered = 0
            dropped = 0
            for record_id in sorted(missing):
                text = self._meta.get(record_id) or ""
                try:
                    vec = np.expand_dims(self.embedding_service.embed(text), axis=0)
                    index = self._ensure_index(vec.shape[1])
                    index.add_with_ids(vec, np.array([record_id], dtype="int64"))
                    recovered += 1
                except Exception as exc:
                    logger.error("补齐向量失败，丢弃该条元数据：recordId=%s, error=%s", record_id, exc)
                    self._meta.pop(record_id, None)
                    dropped += 1
            # 只要有变更就落盘：原先只在 recovered>0 时落盘，导致"全部补齐失败"的场景
            # 每次启动都重复失败一遍（磁盘上仍留着那些无向量的元数据）。
            if recovered or dropped:
                logger.warning("向量补齐完成：成功 %d 条，丢弃 %d 条，准备重新落盘", recovered, dropped)
                self._save()

    def _quarantine_files(self):
        """把无法加载的索引/元数据另存为 .bak，便于事后排查，避免被后续写入覆盖

        备份名带微秒，避免同一秒内多次重启互相覆盖（单测发现的问题）。
        注意：索引与元数据是一对，任一不可用都会一起隔离。
        """
        stamp = time.strftime("%Y%m%d%H%M%S") + f"{int(time.time() * 1000) % 1000:03d}"
        for path in (self.index_path, self.meta_path):
            try:
                if path.exists():
                    backup = path.with_suffix(path.suffix + f".{stamp}.bak")
                    os.replace(path, backup)
                    logger.warning("已备份可疑文件：%s -> %s", path.name, backup.name)
            except Exception as exc:
                logger.error("备份可疑文件失败：%s（%s）", path, exc)

    def _save(self) -> bool:
        """
        持久化索引与元数据（原子写），返回是否成功

        **写入顺序：先元数据、后索引。**
        元数据是事实来源（recordId -> 原文），索引只是由它派生的向量数据。
        若反过来先写索引，两步之间崩溃会留下"索引里有向量、元数据里没有对应条目"的状态，
        检索时就会出现 matched=True 但 matched_text=None（P5-68）；
        按现在的顺序崩溃只会让索引暂时落后，`_load` 会按元数据补齐，且不会产生无主数据。

        每个文件仍是"临时文件 + os.replace"原子替换，失败返回 False —— 调用方必须据此告警，
        否则会出现"接口报成功、磁盘上什么都没有、重启即丢数据"的静默故障（该问题由离线单测发现）。
        """
        with self._lock:
            try:
                # 1) 先落元数据（事实来源），并 fsync 保证真正写到磁盘再改索引
                tmp_meta = self.meta_path.with_suffix(".json.tmp")
                with open(tmp_meta, "w", encoding="utf-8") as f:
                    json.dump(self._meta, f, ensure_ascii=False)
                    f.flush()
                    os.fsync(f.fileno())
                os.replace(tmp_meta, self.meta_path)

                # 2) 再落索引
                if self._index is not None:
                    import faiss
                    tmp_index = self.index_path.with_suffix(".index.tmp")
                    faiss.write_index(self._index, str(tmp_index))
                    os.replace(tmp_index, self.index_path)
                return True
            except Exception as exc:
                logger.error("去重索引持久化失败（内存索引仍可用，但重启后会丢失）：%s", exc)
                return False

    def add(self, record_id: int, text: str):
        """
        新增一条已产生结论的消息到索引

        Raises:
            RuntimeError: 索引/元数据写盘失败时抛出，避免调用方误以为已持久化
        """
        text = (text or "").strip()
        if len(text) < self.min_text_length:
            logger.info("文本过短（%d < %d），跳过索引：recordId=%s", len(text), self.min_text_length, record_id)
            return
        with self._lock:
            try:
                vec = self.embedding_service.embed(text)
            except Exception as exc:
                logger.error("Embedding 不可用，本次不写入索引（避免元数据与向量不一致）：%s", exc)
                return

            try:
                import faiss
                if self._index is None:
                    dim = vec.shape[0]
                    base_index = faiss.IndexFlatIP(dim)
                    self._index = faiss.IndexIDMap(base_index)
                vec = np.expand_dims(vec, axis=0)
                ids = np.array([record_id], dtype="int64")
                self._index.add_with_ids(vec, ids)
                self._meta[record_id] = text
            except Exception as exc:
                logger.error("写入向量索引失败：recordId=%s, error=%s", record_id, exc)
                return
            saved = self._save()
        if saved:
            logger.info("已加入去重索引：recordId=%s", record_id)
        else:
            # 关键：不能静默成功，否则上层（Java /index/add）会以为索引已更新
            raise RuntimeError(f"索引已写入内存但持久化失败，重启后会丢失：recordId={record_id}")

    def search(self, text: str) -> Tuple[bool, Optional[int], float, Optional[str]]:
        """
        检索最相似的历史消息

        Returns:
            (是否命中, record_id, 相似度, 命中文本)
        """
        text = (text or "").strip()
        if not text or len(text) < self.min_text_length:
            return False, None, 0.0, None
        with self._lock:
            if self._index is None or not self._meta:
                return False, None, 0.0, None
            try:
                vec = self.embedding_service.embed(text)
            except Exception as exc:
                logger.warning("Embedding 不可用，跳过语义去重：%s", exc)
                return False, None, 0.0, None

            import faiss
            vec = np.expand_dims(vec, axis=0)
            # 多取几个候选：万一索引里残留了无元数据的向量，可以顺位取下一个，
            # 而不是返回 matched=True 却没有 matched_text（P5-68 的表现之一）
            k = max(self.top_k, min(int(self._index.ntotal), 5))
            scores, ids = self._index.search(vec, k)
            if ids is None or len(ids[0]) == 0:
                return False, None, 0.0, None

            for candidate, raw_score in zip(ids[0], scores[0]):
                idx = int(candidate)
                score = float(raw_score)
                if idx < 0:
                    continue
                if idx not in self._meta:
                    logger.warning("索引中存在无元数据的向量，已跳过：recordId=%s", idx)
                    continue
                if score < self.threshold:
                    return False, None, score, None
                return True, idx, score, self._meta.get(idx)
            return False, None, 0.0, None

