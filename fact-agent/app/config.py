"""
Agent 配置模块

所有配置均通过环境变量（或 `.env`）注入，便于部署时替换。

**环境变量在"实例化时"读取，而不是在"导入时"（P5-69）**：
早期实现把 `os.getenv(...)` 写在 dataclass 的字段默认值里，而 dataclass 的默认值在**类定义时**
（即模块导入时）就求值一次，之后改环境变量再 `Settings()` 也不会生效——
这既让测试只能靠改属性绕过，也让"想在同一进程里换配置"变得不可能。
现在字段默认值只作为**兜底值**，`__post_init__` 再按字段名逐个读取环境变量覆盖。
"""
import os
from dataclasses import dataclass, fields
from pathlib import Path
from typing import Any, Dict

# fact-agent/ 项目根目录
_ROOT = Path(__file__).resolve().parents[1]

# 固定按"项目根目录下的 .env"加载，避免因启动目录不同而读到不同配置
_ENV_FILE = _ROOT / ".env"
try:
    from dotenv import load_dotenv
    load_dotenv(_ENV_FILE if _ENV_FILE.exists() else None)
except ImportError:
    pass

_TRUE_VALUES = ("1", "true", "yes", "on")

# 字段名与环境变量名不一致的映射（其余字段同名）
# 注意：早期实现直接在字段默认值里写 os.getenv("AGENT_PORT")，改成"按字段名读环境变量"后
# 必须显式声明这类映射，否则 AGENT_HOST / AGENT_PORT / AGENT_DATA_DIR 会被静默忽略。
_ENV_NAMES = {
    "HOST": "AGENT_HOST",
    "PORT": "AGENT_PORT",
    "DATA_DIR": "AGENT_DATA_DIR",
}

# 需要按"相对 fact-agent/ 解析路径"处理的字段
_PATH_FIELDS = {"DATA_DIR"}
# 需要按"既可能是模型名、也可能是本地目录"处理的字段
_MODEL_REF_FIELDS = {"EMBEDDING_MODEL"}


def env_name_of(field_name: str) -> str:
    """字段名对应的环境变量名"""
    return _ENV_NAMES.get(field_name, field_name)


def _coerce(name: str, raw: str, default: Any) -> Any:
    """
    按兜底值的类型转换环境变量

    转换失败时回退兜底值（配置写错不应该让服务起不来，但必须能被发现——见 Settings.warnings）。
    """
    if isinstance(default, bool):
        return raw.strip().lower() in _TRUE_VALUES
    if isinstance(default, int):
        return int(raw)
    if isinstance(default, float):
        return float(raw)
    return raw


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
    """Agent 服务配置（字段默认值只是兜底，实际取值由环境变量在实例化时覆盖）"""

    # 服务配置：默认只监听回环，Agent 只需被同机 Java 调用（跨机部署再改 0.0.0.0）
    HOST: str = "127.0.0.1"
    PORT: int = 8001

    # LLM 配置（OpenAI 兼容接口，可配 Qwen / DeepSeek / GPT / Kimi 等）
    LLM_BASE_URL: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    LLM_API_KEY: str = ""
    LLM_MODEL: str = "qwen-plus"
    LLM_TIMEOUT: int = 60
    LLM_TEMPERATURE: float = 0.1
    LLM_RETRY: int = 2

    # 搜索配置（dummy / tavily / bocha / serpapi，默认 dummy 不联网，便于本地联调）
    SEARCH_TOOL: str = "dummy"
    SEARCH_API_KEY: str = ""
    SEARCH_TOP_K: int = 10
    SEARCH_FETCH_TOP: int = 3
    SEARCH_PAGE_TIMEOUT: int = 10
    SEARCH_TIMEOUT: int = 20
    # 单条证据抓取正文的最大保留字符数（与原始摘要拼接后截断）
    SEARCH_PAGE_MAX_CHARS: int = 1500

    # 检测总预算（秒）：单次 /detect 的整体耗时上限，
    # 用于给 LLM / 搜索 / 抓取分配剩余时间，必须小于 Java 侧读超时（fact-check.agent.timeout）
    DETECT_TOTAL_BUDGET: int = 45
    # 待检测文本最大长度，超出部分截断（产品文档限制 1-5000 字）
    DETECT_MAX_TEXT_LENGTH: int = 5000

    # 去重配置（S10：阈值与最短文本长度，配置文件/环境变量可调）
    DEDUP_THRESHOLD: float = 0.92
    DEDUP_MIN_TEXT_LENGTH: int = 8
    DEDUP_TOP_K: int = 1

    # Embedding 模型配置（支持 HuggingFace 名称或本地目录，相对路径相对 fact-agent/ 解析）
    EMBEDDING_MODEL: str = "BAAI/bge-small-zh-v1.5"

    # 数据目录（FAISS 索引与元数据持久化；相对路径相对 fact-agent/ 解析）
    DATA_DIR: Path = _ROOT / "data"

    # 内部鉴权 token（Java 调用时必填；留空则内部接口一律 401）
    INTERNAL_TOKEN: str = ""

    def __post_init__(self):
        self.warnings: list = []
        self._apply_env_overrides()

    def _apply_env_overrides(self):
        """按字段名读取环境变量并覆盖（导入后改环境变量同样生效，只要重新实例化）"""
        for field in fields(self):
            name = field.name
            if name == "warnings":
                continue
            env_name = env_name_of(name)
            raw = os.getenv(env_name)
            if raw is None or raw == "":
                continue
            current = getattr(self, name)
            try:
                if name in _PATH_FIELDS:
                    setattr(self, name, _get_path(env_name, current))
                elif name in _MODEL_REF_FIELDS:
                    setattr(self, name, _get_model_ref(env_name, str(current)))
                else:
                    setattr(self, name, _coerce(name, raw, current))
            except (TypeError, ValueError) as exc:
                self.warnings.append(
                    f"{env_name} 环境变量取值非法（{raw!r}），已使用默认值 {current!r}：{exc}")

    def describe(self) -> Dict[str, Any]:
        """关键配置快照（供启动自检打印；不含任何密钥）"""
        return {
            "host": self.HOST,
            "port": self.PORT,
            "search_tool": self.SEARCH_TOOL,
            "search_offline": self.SEARCH_TOOL == "dummy",
            "llm_model": self.LLM_MODEL,
            "llm_configured": bool(self.LLM_API_KEY),
            "detect_budget": self.DETECT_TOTAL_BUDGET,
            "internal_auth": bool(self.INTERNAL_TOKEN),
        }


settings = Settings()
