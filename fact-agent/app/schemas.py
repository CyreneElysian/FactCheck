"""
Pydantic 数据模型定义
"""
from typing import List, Optional
from pydantic import BaseModel, Field


class DedupRequest(BaseModel):
    """语义去重请求"""
    text: str = Field(..., description="待去重文本")


class DedupResponse(BaseModel):
    """语义去重响应"""
    matched: bool = Field(False, description="是否命中相似历史消息")
    record_id: Optional[int] = Field(None, description="命中的记录 ID")
    score: float = Field(0.0, description="余弦相似度")
    matched_text: Optional[str] = Field(None, description="命中的历史文本")


class IndexAddRequest(BaseModel):
    """新增向量索引请求"""
    record_id: int = Field(..., description="记录 ID")
    text: str = Field(..., description="消息文本")


class DetectRequest(BaseModel):
    """LLM 事实检测请求"""
    text: str = Field(..., description="待检测文本")


class Evidence(BaseModel):
    """证据"""
    title: str = ""
    url: str = ""
    snippet: str = ""
    source: str = ""
    source_credibility: float = Field(0.0, description="来源可信度 0-1")
    relevance_score: float = Field(0.0, description="相关度 0-1")


class DetectResponse(BaseModel):
    """LLM 事实检测响应"""
    can_judge: bool = Field(False, description="LLM 是否能给出结论")
    verdict: Optional[str] = Field(None, description="TRUE / FALSE / UNVERIFIED")
    confidence: Optional[float] = Field(None, description="置信度 0-1")
    reasoning_chain: Optional[str] = Field(None, description="推理链")
    evidence: List[Evidence] = Field(default_factory=list, description="证据列表")
    human_reason: Optional[str] = Field(None, description="需要人工的原因")


class HealthResponse(BaseModel):
    """健康检查响应"""
    status: str = "ok"
    search_tool: str = ""
    embedding_model: str = ""
    llm_model: str = ""
