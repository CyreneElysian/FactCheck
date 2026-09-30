"""
语义去重服务（小模型）

使用 FAISS IndexFlatIP + Embedding 向量做余弦相似度检索。
索引与元数据持久化到本地 data 目录，启动时自动加载。
"""
import json
import logging
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
        self._load()

    def _load(self):
        """启动时加载已持久化的索引与元数据"""
        try:
            if self.index_path.exists() and self.meta_path.exists():
                import faiss
                self._index = faiss.read_index(str(self.index_path))
                with open(self.meta_path, "r", encoding="utf-8") as f:
                    self._meta = {int(k): v for k, v in json.load(f).items()}
                logger.info("去重索引加载完成，共 %d 条", self._index.ntotal)
        except Exception as exc:
            logger.warning("去重索引加载失败，将重建：%s", exc)
            self._index = None
            self._meta = {}

    def _save(self):
        """持久化索引与元数据"""
        try:
            if self._index is not None:
                import faiss
                faiss.write_index(self._index, str(self.index_path))
            with open(self.meta_path, "w", encoding="utf-8") as f:
                json.dump(self._meta, f, ensure_ascii=False)
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
        try:
            vec = self.embedding_service.embed(text)
        except Exception as exc:
            logger.warning("Embedding 不可用，跳过向量索引（仅保存元数据）：%s", exc)
            self._meta[record_id] = text
            self._save()
            return

        import faiss
        if self._index is None:
            dim = vec.shape[0]
            base_index = faiss.IndexFlatIP(dim)
            self._index = faiss.IndexIDMap(base_index)
        vec = np.expand_dims(vec, axis=0)
        ids = np.array([record_id], dtype="int64")
        self._index.add_with_ids(vec, ids)
        self._meta[record_id] = text
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
