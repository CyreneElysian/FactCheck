"""
LLM 服务（OpenAI 兼容接口）

通过 HTTP 调用 /chat/completions，支持 Qwen / DeepSeek / GPT / Kimi 等。
"""
import logging
import math
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

    def chat(self, messages: List[dict], timeout: Optional[int] = None,
             deadline: Optional[float] = None) -> str:
        """
        调用 chat/completions，返回 assistant 文本；失败自动重试

        Args:
            messages: OpenAI 格式消息列表
            timeout: 本次调用的超时（秒）。不传则用构造时的默认值；
                     检测流程会按整体预算传入剩余时间，避免超出总预算。
            deadline: 整体预算的绝对截止时刻（``time.monotonic()`` 基准）。
                     传入后，**每次重试都会按剩余时间重新计算超时**，
                     并在预算耗尽时停止重试。

        ⚠️ 为什么要 deadline：原先只在进入本方法前算一次 ``remaining``，
        然后每次重试都用同一个超时（``LLM_RETRY`` 默认 2，即最多 3 次），
        再加上退避 sleep，最坏情况把单次 ``/detect`` 拉到约 3 倍预算，
        直接击穿 Java 侧 90s 读超时，出现"Java 已判失败、Agent 仍在跑并可能写索引"的状态不一致。
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
            # 每次重试都按剩余预算重新收紧超时：
            # 不能沿用上一轮的 effective_timeout，否则 3 次重试会各给一份完整超时。
            if deadline is not None:
                left = deadline - time.monotonic()
                if left <= 0:
                    logger.warning("整体预算已耗尽，放弃第 %d 次 LLM 调用", attempt + 1)
                    break
                effective_timeout = max(1, min(effective_timeout, math.ceil(left)))
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
                    backoff = 1 * (attempt + 1)
                    if deadline is not None:
                        left = deadline - time.monotonic()
                        # 退避后已经没有时间再发一次请求，就不必白等
                        if left <= backoff:
                            logger.warning("剩余预算不足 %.1fs，不再重试 LLM", left)
                            break
                    time.sleep(backoff)
        raise RuntimeError(f"LLM 调用失败：{last_exc}") from last_exc
