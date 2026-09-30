"""
Agent 配置模块

所有配置均通过环境变量注入，便于部署时替换。
"""
import os
from dataclasses import dataclass
from pathlib import Path

# fact-agent/ 项目根目录
_ROOT = Path(__file__).resolve().parents[1]

# 固定按"项目根目录下的 .env"加载，避免因启动目录不同而读到不同配置
_ENV_FILE = _ROOT / ".env"
try:
    from dotenv import load_dotenv
    load_dotenv(_ENV_FILE if _ENV_FILE.exists() else None)
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


def _get_path(name: str, default: Path) -> Path:
    """

    读取目录类配置：相对路径一律相对 fact-agent/ 解析，
    避免从不同工作目录启动时读写到不同的 data 目录。
    """
    value = os.getenv(name)
    if not value:
        return default
    path = Path(value)
    return path if path.is_absolute() else (_ROOT / path).resolve()


def _get_model_ref(name: str, default: str) -> str:
    """

    读取 embedding 模型配置：既支持 HuggingFace 模型名（如 BAAI/bge-small-zh-v1.5），
    也支持本地目录（./models/... 或绝对路径）；后者统一解析为绝对路径。
    """
    value = os.getenv(name)
    if not value:
        return default
    path = Path(value)
    looks_like_path = path.is_absolute() or value.startswith(("./", "../", ".\\", "..\\"))
    if looks_like_path:
        return str(path if path.is_absolute() else (_ROOT / path).resolve())
    return value


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
    SEARCH_TOP_K: int = _get_int("SEARCH_TOP_K", 10)
    SEARCH_FETCH_TOP: int = _get_int("SEARCH_FETCH_TOP", 3)
    SEARCH_PAGE_TIMEOUT: int = _get_int("SEARCH_PAGE_TIMEOUT", 10)
    SEARCH_TIMEOUT: int = _get_int("SEARCH_TIMEOUT", 20)
    # 单条证据抓取正文的最大保留字符数（与原始摘要拼接后截断）
    SEARCH_PAGE_MAX_CHARS: int = _get_int("SEARCH_PAGE_MAX_CHARS", 1500)

    # 检测总预算（秒）：单次 /detect 的整体耗时上限，
    # 用于给 LLM / 搜索 / 抓取分配剩余时间，必须小于 Java 侧读超时（fact-check.agent.timeout）
    DETECT_TOTAL_BUDGET: int = _get_int("DETECT_TOTAL_BUDGET", 45)
    # 待检测文本最大长度，超出部分截断（产品文档限制 1-5000 字）
    DETECT_MAX_TEXT_LENGTH: int = _get_int("DETECT_MAX_TEXT_LENGTH", 5000)

    # 去重配置（S10：阈值与最短文本长度，配置文件/环境变量可调）
    DEDUP_THRESHOLD: float = _get_float("DEDUP_THRESHOLD", 0.92)
    DEDUP_MIN_TEXT_LENGTH: int = _get_int("DEDUP_MIN_TEXT_LENGTH", 8)
    DEDUP_TOP_K: int = _get_int("DEDUP_TOP_K", 1)

    # Embedding 模型配置（支持 HuggingFace 名称或本地目录，相对路径相对 fact-agent/ 解析）
    EMBEDDING_MODEL: str = _get_model_ref("EMBEDDING_MODEL", "BAAI/bge-small-zh-v1.5")

    # 数据目录（FAISS 索引与元数据持久化；相对路径相对 fact-agent/ 解析）
    DATA_DIR: Path = _get_path("AGENT_DATA_DIR", _ROOT / "data")

    # 内部鉴权 token（Java 调用时可开启）
    INTERNAL_TOKEN: str = os.getenv("INTERNAL_TOKEN", "")


settings = Settings()
