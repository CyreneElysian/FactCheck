"""
语义去重服务（小模型）

使用 FAISS IndexFlatIP + Embedding 向量做余弦相似度检索。
索引与元数据持久化到本地 data 目录，启动时自动加载。

持久化安全（重要）：
- 写盘采用"临时文件 + 原子替换"，避免写一半崩溃留下半截索引；
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
            if self.index_path.exists() and self.meta_path.exists():
                import faiss
                index = faiss.read_index(str(self.index_path))
                with open(self.meta_path, "r", encoding="utf-8") as f:
                    meta = {int(k): v for k, v in json.load(f).items()}
                self._index = index
                self._meta = meta
                logger.info("去重索引加载完成，共 %d 条", index.ntotal)
            else:
                logger.info("去重索引不存在，将新建（data_dir=%s）", self.data_dir)
        except Exception as exc:
            logger.error("去重索引加载失败：%s；已将可疑文件另存为 .bak 后重建，不会覆盖原始数据", exc)
            self._index = None
            self._meta = {}
            self._quarantine_files()

    def _quarantine_files(self):
        """把无法加载的索引/元数据另存为 .bak，便于事后排查，避免被后续写入覆盖"""
        stamp = time.strftime("%Y%m%d%H%M%S")
        for path in (self.index_path, self.meta_path):
            try:
                if path.exists():
                    backup = path.with_suffix(path.suffix + f".{stamp}.bak")
                    os.replace(path, backup)
                    logger.warning("已备份可疑文件：%s -> %s", path.name, backup.name)
            except Exception as exc:
                logger.error("备份可疑文件失败：%s（%s）", path, exc)

    def _save(self):
        """
        持久化索引与元数据（原子写）

        先写临时文件再 os.replace 覆盖，保证任何时刻磁盘上都是一个完整文件。
        """
        with self._lock:
            try:
                if self._index is not None:
                    import faiss
                    tmp_index = self.index_path.with_suffix(".index.tmp")
                    faiss.write_index(self._index, str(tmp_index))
                    os.replace(tmp_index, self.index_path)

                tmp_meta = self.meta_path.with_suffix(".json.tmp")
                with open(tmp_meta, "w", encoding="utf-8") as f:
                    json.dump(self._meta, f, ensure_ascii=False)
                os.replace(tmp_meta, self.meta_path)
            except Exception as exc:
                logger.error("去重索引持久化失败：%s", exc)

    def add(self, record_id: int, text: str):
        """
        新增一条已产生结论的消息到索引
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
            self._save()
        logger.info("已加入去重索引：recordId=%s", record_id)

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
            scores, ids = self._index.search(vec, self.top_k)
            if ids is None or len(ids[0]) == 0:
                return False, None, 0.0, None
            idx = int(ids[0][0])
            score = float(scores[0][0])
            if idx < 0 or score < self.threshold:
                return False, None, score, None
            record_id = idx
            matched_text = self._meta.get(idx)
            return True, record_id, score, matched_text

