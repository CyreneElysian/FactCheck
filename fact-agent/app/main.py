"""
FastAPI 入口

启动命令：
    uvicorn app.main:app --host 0.0.0.0 --port 8001

部署约束：
- 语义去重索引是进程内内存态，必须以**单 worker** 运行（不要加 --workers）。
- 单次 /detect 受 DETECT_TOTAL_BUDGET 约束，应小于 Java 侧 fact-check.agent.timeout。
"""
import logging

from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse

from .config import settings
from .schemas import (
    DedupRequest,
    DedupResponse,
    DetectRequest,
    DetectResponse,
    HealthResponse,
    IndexAddRequest,
)
from .services.dedup_service import DedupService
from .services.detect_service import DetectService
from .services.embedding import EmbeddingService
from .services.llm import OpenAICompatibleLLM
from .services.search import (
    describe_tool,
    get_search_tool,
    is_offline_tool,
    validate_tool_config,
)

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger(__name__)

app = FastAPI(title="FactCheck Agent", version="1.0.0")

# 组装服务
embedding_service = EmbeddingService(settings.EMBEDDING_MODEL)
dedup_service = DedupService(
    embedding_service=embedding_service,
    data_dir=settings.DATA_DIR,
    threshold=settings.DEDUP_THRESHOLD,
    min_text_length=settings.DEDUP_MIN_TEXT_LENGTH,
    top_k=settings.DEDUP_TOP_K,
)
search_tool = get_search_tool(settings.SEARCH_TOOL, settings.SEARCH_API_KEY)
llm = OpenAICompatibleLLM(
    base_url=settings.LLM_BASE_URL,
    api_key=settings.LLM_API_KEY,
    model=settings.LLM_MODEL,
    timeout=settings.LLM_TIMEOUT,
    temperature=settings.LLM_TEMPERATURE,
    retry=settings.LLM_RETRY,
)
detect_service = DetectService(search_tool=search_tool, llm=llm)

# 启动自检：把"配置看起来能用、运行时其实不联网/必失败"的情况提前暴露
_tool_problem = validate_tool_config(settings.SEARCH_TOOL, settings.SEARCH_API_KEY)
if _tool_problem:
    logger.error("搜索源配置有问题：%s", _tool_problem)
elif is_offline_tool(search_tool):
    logger.warning(
        "当前搜索源为 dummy（不联网）：/detect 将只依赖 LLM 自身知识判断。"
        "如需联网核查，请设置 SEARCH_TOOL=tavily|bocha|serpapi 并配置 SEARCH_API_KEY"
    )
else:
    logger.info("搜索源已启用：%s（top_k=%d, fetch_top=%d）",
                describe_tool(search_tool), settings.SEARCH_TOP_K, settings.SEARCH_FETCH_TOP)
if not settings.LLM_API_KEY:
    logger.error("LLM_API_KEY 未配置，/detect 将无法调用大模型")
logger.info("检测时间预算：%ds（Java 侧读超时应大于该值）", settings.DETECT_TOTAL_BUDGET)


def _check_token(x_internal_token: str = Header("", alias="X-Internal-Token")):
    """内部接口鉴权，未配置 INTERNAL_TOKEN 时不校验"""
    if settings.INTERNAL_TOKEN and x_internal_token != settings.INTERNAL_TOKEN:
        raise HTTPException(status_code=401, detail="invalid internal token")


@app.exception_handler(Exception)
async def unhandled_exception_handler(request: Request, exc: Exception):
    """兜底异常处理：避免把堆栈以裸 500 抛给调用方"""
    logger.exception("未处理异常：path=%s", request.url.path)
    return JSONResponse(
        status_code=500,
        content={"detail": f"内部错误：{type(exc).__name__}"},
    )


@app.get("/health", response_model=HealthResponse)
def health():
    """健康检查（浅检查：只回显配置，不校验外部依赖可用性）"""
    return HealthResponse(
        status="ok",
        search_tool=describe_tool(search_tool),
        search_offline=is_offline_tool(search_tool),
        embedding_model=settings.EMBEDDING_MODEL,
        llm_model=settings.LLM_MODEL,
        llm_configured=bool(settings.LLM_API_KEY),
    )


@app.post("/dedup", response_model=DedupResponse, dependencies=[])
def dedup(req: DedupRequest, x_internal_token: str = Header("", alias="X-Internal-Token")):
    """语义去重：判断消息是否与历史消息相似"""
    _check_token(x_internal_token)
    matched, record_id, score, matched_text = dedup_service.search(req.text)
    logger.info("语义去重：matched=%s, recordId=%s, score=%.4f", matched, record_id, score)
    return DedupResponse(matched=matched, record_id=record_id, score=score, matched_text=matched_text)


@app.post("/index/add", response_model=dict, dependencies=[])
def index_add(req: IndexAddRequest, x_internal_token: str = Header("", alias="X-Internal-Token")):
    """新增向量索引：消息产生最终结论后调用"""
    _check_token(x_internal_token)
    try:
        dedup_service.add(req.record_id, req.text)
    except Exception as exc:
        logger.exception("写入向量索引失败：recordId=%s", req.record_id)
        return JSONResponse(status_code=500, content={"detail": f"索引写入失败：{exc}"})
    return {"status": "ok"}


@app.post("/detect", response_model=DetectResponse, dependencies=[])
def detect(req: DetectRequest, x_internal_token: str = Header("", alias="X-Internal-Token")):
    """LLM 事实检测

    任何内部异常都转换成 error=true 的正常响应，
    便于 Java 侧区分"检测失败"（可重试）与"AI 无法判断"（转人工）。
    """
    _check_token(x_internal_token)
    try:
        return detect_service.detect(req.text)
    except Exception as exc:
        logger.exception("事实检测异常：%s", exc)
        return DetectResponse(
            can_judge=False,
            human_reason=f"检测服务内部错误：{type(exc).__name__}",
            error=True,
        )
