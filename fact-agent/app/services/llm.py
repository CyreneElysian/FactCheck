"""
LLM 服务（OpenAI 兼容接口）

通过 HTTP 调用 /chat/completions，支持 Qwen / DeepSeek / GPT / Kimi 等。
"""
import logging
import time
from typing import List, Optional

import requests

logger = logging.getLogger(__name__)


class OpenAICompatibleLLM:
    """OpenAI 兼容 LLM 客户端"""

    def __init__(self, base_url: str, api_key: str, model: str,
                 timeout: int = 60, temperature: float = 0.1, retry: int = 2):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model
        self.timeout = timeout
        self.temperature = temperature
        self.retry = retry

    def chat(self, messages: List[dict], timeout: Optional[int] = None) -> str:
        """
        调用 chat/completions，返回 assistant 文本；失败自动重试

        Args:
            messages: OpenAI 格式消息列表
            timeout: 本次调用的超时（秒）。不传则用构造时的默认值；
                     检测流程会按整体预算传入剩余时间，避免超出总预算。
        """
        if not self.api_key:
            logger.warning("LLM_API_KEY 未配置，返回空结果")
            return ""

        effective_timeout = timeout if timeout and timeout > 0 else self.timeout
        url = f"{self.base_url}/chat/completions"
        payload = {
            "model": self.model,
            "messages": messages,
            "temperature": self.temperature,
        }
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }

        last_exc = None
        for attempt in range(self.retry + 1):
            try:
                resp = requests.post(url, json=payload, headers=headers, timeout=effective_timeout)
                resp.raise_for_status()
                data = resp.json()
                return data["choices"][0]["message"]["content"]
            except Exception as exc:
                last_exc = exc
                logger.warning("LLM 调用失败（第 %d/%d 次，timeout=%ss）：%s",
                               attempt + 1, self.retry + 1, effective_timeout, exc)
                if attempt < self.retry:
                    time.sleep(1 * (attempt + 1))
        raise RuntimeError(f"LLM 调用失败：{last_exc}") from last_exc
