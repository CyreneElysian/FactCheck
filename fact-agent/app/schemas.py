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
    """LLM 事实检测请求

    category 为**可选**：它是前端"消息分类"下拉框的取值
    （其他/健康/社会/财经/科技/政策），Agent 据此追加分类专属的核查注意事项。
    刻意不校验取值集合——分类写错、旧版 Java 不带该字段，
    都应退回通用提示词照常检测，而不是让检测失败。
    """

    text: str = Field(..., description="待检测文本")
    category: Optional[str] = Field(None, description="消息分类（可选，用于追加分类专属提示）")


class Evidence(BaseModel):
    """证据

    source_credibility / relevance_score 允许缺省：
    模型不一定会给出这两个值，缺失时由 detect_service 用域名规则与
    "位置 + 词面重合"启发式补齐，保证下发给 Java 时均为 0-1 的数值。
    """
    title: str = ""
    url: str = ""
    snippet: str = ""
    source: str = ""
    source_credibility: Optional[float] = Field(None, description="来源可信度 0-1")
    relevance_score: Optional[float] = Field(None, description="相关度 0-1")


class DetectResponse(BaseModel):
    """LLM 事实检测响应"""
    can_judge: bool = Field(False, description="LLM 是否能给出结论")
    verdict: Optional[str] = Field(None, description="TRUE / FALSE / UNVERIFIED")
    confidence: Optional[float] = Field(None, description="置信度 0-1")
    reasoning_chain: Optional[str] = Field(None, description="推理链")
    evidence: List[Evidence] = Field(default_factory=list, description="证据列表")
    human_reason: Optional[str] = Field(None, description="需要人工的原因")
    error: bool = Field(
        False,
        description="是否为服务侧故障（LLM 调用失败 / 输出无法解析）。"
                    "为 True 时 Java 侧应标记为“检测失败”而非“待人工诊断”，"
                    "避免模型抖动把人工队列灌满。"
    )


class HealthResponse(BaseModel):
    """健康检查响应"""
    status: str = "ok"
    search_tool: str = ""
    search_offline: bool = Field(False, description="搜索源是否不联网（dummy），为 True 时检测只依赖模型自身知识")
    embedding_model: str = ""
    llm_model: str = ""
    llm_configured: bool = Field(False, description="是否已配置 LLM_API_KEY")
    internal_auth_enabled: bool = Field(
        False,
        description="是否启用了内部鉴权（INTERNAL_TOKEN）。为 False 时内部接口一律返回 401（fail-closed）"
    )
    credibility_rules: str = Field("", description="当前生效的来源可信度规则来源与规模，便于确认改的规则文件是否生效")
