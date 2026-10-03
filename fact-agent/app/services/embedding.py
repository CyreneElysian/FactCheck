"""
Embedding 服务

默认使用 sentence-transformers 加载 bge-small-zh-v1.5 模型。
支持两种配置方式：
1. HuggingFace 模型名：BAAI/bge-small-zh-v1.5（首次会联网下载）
2. 本地模型目录：把 EMBEDDING_MODEL 配成下载好的模型文件夹路径，
   程序检测到路径存在时自动启用离线加载，不再访问 HuggingFace。
"""
import logging
import os
from pathlib import Path

import numpy as np

logger = logging.getLogger(__name__)


class EmbeddingService:
    """文本向量化服务"""

    def __init__(self, model_name: str):
        self.model_name = model_name
        self._model = None

    def _load(self):
        if self._model is None:
            try:
                from sentence_transformers import SentenceTransformer
            except ImportError as exc:
                raise RuntimeError(
                    "未安装 sentence-transformers，请先执行 pip install -r requirements.txt"
                ) from exc

            try:
                local_path = Path(self.model_name).expanduser().resolve()
                if local_path.exists():
                    # 本地路径存在：强制离线加载，避免再访问 HuggingFace
                    os.environ["TRANSFORMERS_OFFLINE"] = "1"
                    os.environ["HF_HUB_OFFLINE"] = "1"
                    logger.info("从本地路径加载 Embedding 模型：%s", local_path)
                    self._model = SentenceTransformer(str(local_path))
                else:
                    logger.info("从 HuggingFace 加载 Embedding 模型：%s", self.model_name)
                    self._model = SentenceTransformer(self.model_name)
            except Exception as exc:
                raise RuntimeError(f"Embedding 模型加载失败：{exc}") from exc
        return self._model

    def embed(self, text: str) -> np.ndarray:
        """
        将文本转换为归一化向量
        """
        model = self._load()
        vec = model.encode([text], normalize_embeddings=True, show_progress_bar=False)[0]
        return np.asarray(vec, dtype="float32")

    # 说明（P5-51）：这里原有 `dim` 属性（返回向量维度），全项目零引用，已删除。
    # DedupService 建索引时直接用「首个向量」的 shape 取维度，不需要额外的 getter。
