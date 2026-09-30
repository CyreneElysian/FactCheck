"""
Agent 配置模块

所有配置均通过环境变量注入，便于部署时替换。
"""
import os
from dataclasses import dataclass
from pathlib import Path

try:
    from dotenv import load_dotenv
    load_dotenv()
except ImportError:
    pass


def _get_float(name: str, default: float) -> float:
    value = os.getenv(name)
    return float(value) if value else default


def _get_int(name: str, default: int) -> int:
    value = os.getenv(name)
    return int(value) if value else default


def _get_bool(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.lower() in ("1", "true", "yes", "on")


@dataclass
class Settings:
    """Agent 服务配置"""

    # 服务配置
    HOST: str = os.getenv("AGENT_HOST", "0.0.0.0")
    PORT: int = _get_int("AGENT_PORT", 8001)

    # LLM 配置（OpenAI 兼容接口，可配 Qwen / DeepSeek / GPT / Kimi 等）
    LLM_BASE_URL: str = os.getenv("LLM_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")
    LLM_API_KEY: str = os.getenv("LLM_API_KEY", "")
    LLM_MODEL: str = os.getenv("LLM_MODEL", "qwen-plus")
    LLM_TIMEOUT: int = _get_int("LLM_TIMEOUT", 60)
    LLM_TEMPERATURE: float = _get_float("LLM_TEMPERATURE", 0.1)
    LLM_RETRY: int = _get_int("LLM_RETRY", 2)

    # 搜索配置（dummy / tavily / bocha / serpapi，默认 dummy 不联网，便于本地联调）
    SEARCH_TOOL: str = os.getenv("SEARCH_TOOL", "dummy")
    SEARCH_API_KEY: str = os.getenv("SEARCH_API_KEY", "")
    SEARCH_TOP_K: int = _get_int("SEARCH_TOP_K", 5)
    SEARCH_FETCH_TOP: int = _get_int("SEARCH_FETCH_TOP", 2)
    SEARCH_PAGE_TIMEOUT: int = _get_int("SEARCH_PAGE_TIMEOUT", 10)

    # 去重配置（S10：阈值与最短文本长度，配置文件/环境变量可调）
    DEDUP_THRESHOLD: float = _get_float("DEDUP_THRESHOLD", 0.92)
    DEDUP_MIN_TEXT_LENGTH: int = _get_int("DEDUP_MIN_TEXT_LENGTH", 8)
    DEDUP_TOP_K: int = _get_int("DEDUP_TOP_K", 1)

    # Embedding 模型配置
    EMBEDDING_MODEL: str = os.getenv("EMBEDDING_MODEL", "BAAI/bge-small-zh-v1.5")

    # 数据目录（FAISS 索引与元数据持久化）
    DATA_DIR: Path = Path(os.getenv("AGENT_DATA_DIR", str(Path(__file__).resolve().parents[1] / "data")))

    # 内部鉴权 token（Java 调用时可开启）
    INTERNAL_TOKEN: str = os.getenv("INTERNAL_TOKEN", "")


settings = Settings()
