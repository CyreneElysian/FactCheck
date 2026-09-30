"""
事实检测提示词模板
"""

SYSTEM_PROMPT = (
    "你是一个专业的事实核查助手。请根据提供的搜索结果和你自己的知识，"
    "判断用户消息的真假。只输出 JSON，不要输出任何其他文字。"
)

USER_PROMPT_TEMPLATE = """待检测消息：
{text}

搜索结果：
{evidence}

请输出如下 JSON：
{{
  "can_judge": true,
  "verdict": "TRUE",
  "confidence": 0.86,
  "reasoning_chain": "1. ... 2. ...",
  "evidence": [
    {{
      "title": "证据标题",
      "url": "https://...",
      "snippet": "证据摘要",
      "source": "来源",
      "source_credibility": 0.95,
      "relevance_score": 0.93
    }}
  ],
  "human_reason": null
}}

规则：
1. verdict 只能是 TRUE（真）、FALSE（假）、UNVERIFIED（无法判定）之一。
2. 如果搜索结果不足且你无法可靠判断，设置 can_judge=false，verdict 为 null，并在 human_reason 中说明需要人工的原因，同时把找到的相关证据放入 evidence。
3. confidence 取值范围 0 到 1。
4. 推理链分步骤说明判断依据，必须引用证据来源。
"""
