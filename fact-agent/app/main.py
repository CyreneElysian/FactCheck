"""
FastAPI 入口

启动命令：
    uvicorn app.main:app --host 0.0.0.0 --port 8001
"""
import logging

from fastapi import FastAPI, Header, HTTPException

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
from .services.search import get_search_tool

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


def _check_token(x_internal_token: str = Header("", alias="X-Internal-Token")):
    """内部接口鉴权，未配置 INTERNAL_TOKEN 时不校验"""
    if settings.INTERNAL_TOKEN and x_internal_token != settings.INTERNAL_TOKEN:
        raise HTTPException(status_code=401, detail="invalid internal token")


@app.get("/health", response_model=HealthResponse)
def health():
    """健康检查"""
    return HealthResponse(
        status="ok",
        search_tool=settings.SEARCH_TOOL,
        embedding_model=settings.EMBEDDING_MODEL,
        llm_model=settings.LLM_MODEL,
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
    dedup_service.add(req.record_id, req.text)
    return {"status": "ok"}


@app.post("/detect", response_model=DetectResponse, dependencies=[])
def detect(req: DetectRequest, x_internal_token: str = Header("", alias="X-Internal-Token")):
    """LLM 事实检测"""
    _check_token(x_internal_token)
    return detect_service.detect(req.text)
