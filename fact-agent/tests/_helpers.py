"""
测试共享工具：假搜索源、假 LLM、假 Embedding、网络熔断、配置快照。

这里的所有假对象都只在进程内做计算，绝不发起网络请求或加载真实模型。
"""
import hashlib
import json
import math
import socket
import unittest
from typing import Dict, List, Optional
from unittest.mock import MagicMock, patch

import numpy as np

from app.config import settings
from app.services.search import SearchResult, SearchTool

# ----------------------------------------------------------------------
# 网络熔断：任何真实出网尝试都会让测试直接失败，而不是悄悄联网
# ----------------------------------------------------------------------

_LOOPBACK = {"127.0.0.1", "::1", "localhost", "0.0.0.0", ""}
_FAKE_DIM = 64


class _NetworkGuard:
    """替换 socket.socket.connect，禁止测试连到非本机地址"""

    def __init__(self):
        self._original = None
        self.blocked_hosts: List[str] = []

    def install(self):
        if self._original is not None:
            return
        original = socket.socket.connect

        def guarded_connect(sock_self, address, *args, **kwargs):
            host = ""
            try:
                host = str(address[0])
            except Exception:
                host = str(address)
            if host not in _LOOPBACK:
                self.blocked_hosts.append(host)
                raise AssertionError(
                    f"测试禁止访问网络，但检测到对外连接尝试：{host}"
                )
            return original(sock_self, address, *args, **kwargs)

        self._original = original
        socket.socket.connect = guarded_connect

    def uninstall(self):
        if self._original is not None:
            socket.socket.connect = self._original
            self._original = None


_NETWORK_GUARD = _NetworkGuard()


def _stop_patcher(patcher) -> None:
    """幂等地停止一个 patcher（重复 stop 会报 RuntimeError，这里直接忽略）"""
    try:
        patcher.stop()
    except RuntimeError:
        pass


class OfflineTestCase(unittest.TestCase):
    """
    离线测试基类

    统一保证（三道防线）：
    1. requests.get/post 被打桩，任何 HTTP 调用直接失败；
    2. socket 熔断，任何对外 TCP 连接直接失败；
    3. SEARCH_FETCH_TOP 临时置 0，默认连抓取分支都不进入。
    另外，每个用例结束后还原 settings 上被临时改写的字段。
    """

    def setUp(self):
        _NETWORK_GUARD.install()
        self._install_http_guard()
        self._settings_backup: Dict[str, object] = {}
        settings.SEARCH_FETCH_TOP = 0
        self.addCleanup(self._restore_settings)

    def _install_http_guard(self):
        """把 requests 的入口方法打桩：测试里出现任何真实 HTTP 调用都直接判失败

        注意：Python 3.10 没有 TestCase.enterContext（3.11 才加入），
        因此这里用 addCleanup + 幂等 stop 的方式，保证多个打桩互不干扰。
        """
        def blocked(method):
            def _raise(*args, **kwargs):
                target = args[0] if args else kwargs.get("url", "?")
                raise AssertionError(f"测试禁止发起 HTTP 请求，但检测到 {method} {target}")
            return _raise

        import requests
        for name in ("get", "post", "put", "delete", "head", "patch"):
            patcher = patch.object(requests, name, new=MagicMock(side_effect=blocked(name.upper())))
            patcher.start()
            self.addCleanup(_stop_patcher, patcher)

    def _restore_settings(self):
        for name, value in self._settings_backup.items():
            setattr(settings, name, value)
        self._settings_backup.clear()

    def patch_setting(self, name: str, value: object) -> None:
        """临时改写 settings 字段，并在测试结束后自动还原（幂等备份）"""
        if name not in self._settings_backup:
            self._settings_backup[name] = getattr(settings, name)
        setattr(settings, name, value)
        self.addCleanup(self._restore_settings)


# ----------------------------------------------------------------------
# 假搜索源
# ----------------------------------------------------------------------


class FakeSearchTool(SearchTool):
    """假搜索源：不联网，返回预置结果或抛出预置异常"""

    def __init__(self, results: Optional[List[SearchResult]] = None,
                 raise_error: Optional[BaseException] = None):
        self.results = list(results or [])
        self.raise_error = raise_error
        self.calls: List[dict] = []

    def search(self, query: str, top_k: int = 10, timeout: int = 20) -> List[SearchResult]:
        self.calls.append({"query": query, "top_k": top_k, "timeout": timeout})
        if self.raise_error is not None:
            raise self.raise_error
        return list(self.results[:top_k]) if top_k else list(self.results)


# ----------------------------------------------------------------------
# 假 LLM
# ----------------------------------------------------------------------


class FakeLLM:
    """
    假 LLM：带 api_key 属性与 chat(messages, timeout=None) 方法。

    responses 按调用顺序弹出；不足时重复使用最后一条。
    """

    def __init__(self, responses: Optional[List[object]] = None,
                 api_key: str = "fake-key",
                 raise_after: Optional[int] = None,
                 raise_error: Optional[BaseException] = None):
        self.responses = list(responses) if responses is not None else ["[]"]
        self.api_key = api_key
        self.raise_after = raise_after          # 第 N 次（1 起算）调用起抛异常
        self.raise_error = raise_error or RuntimeError("假的 LLM 故障")
        self.seen_messages: List[List[dict]] = []
        self.seen_timeouts: List[Optional[int]] = []

    def chat(self, messages: List[dict], timeout: Optional[int] = None) -> str:
        self.seen_messages.append(messages)
        self.seen_timeouts.append(timeout)
        call_index = len(self.seen_messages)
        if self.raise_after is not None and call_index >= self.raise_after:
            raise self.raise_error
        if not self.responses:
            return "{}"
        if len(self.responses) == 1:
            return self.responses[0]
        return self.responses.pop(0)

    # -- 便捷断言辅助（不放进生产代码，仅测试用） ------------------------

    @property
    def last_messages(self) -> List[dict]:
        """最后一次 chat 调用的 messages（检测链路里第 2 次才是真正的判定）"""
        return self.seen_messages[-1]

    @property
    def last_user_prompt(self) -> str:
        return self.last_messages[-1]["content"]

    @property
    def last_system_prompt(self) -> str:
        return self.last_messages[0]["content"]

    @property
    def call_count(self) -> int:
        return len(self.seen_messages)


def json_text(**payload) -> str:
    """把结构化成合法 JSON 文本（模拟 LLM 输出）"""
    return json.dumps(payload, ensure_ascii=False)


# ----------------------------------------------------------------------
# 假 Embedding
# ----------------------------------------------------------------------


class FakeEmbeddingService:
    """
    确定性假 embedding：把文本哈希成固定维度的归一化向量。

    不加载 bge 模型，不做任何 IO；同一文本永远得到同一向量，
    不同文本几乎不可能相同，足以驱动 FAISS 余弦检索。
    """

    def __init__(self, dim: int = _FAKE_DIM, raise_error: Optional[BaseException] = None):
        self.dim_value = dim
        self.raise_error = raise_error
        self.calls: List[str] = []

    def _raw(self, text: str) -> np.ndarray:
        vec = np.zeros(self.dim_value, dtype="float32")
        for index in range(self.dim_value):
            digest = hashlib.sha256(f"{index}\u0000{text}".encode("utf-8")).digest()
            raw = int.from_bytes(digest[:4], "big")
            vec[index] = (raw / 4294967295.0) * 2.0 - 1.0
        norm = float(np.linalg.norm(vec))
        if norm == 0.0 or math.isnan(norm):
            vec = np.ones(self.dim_value, dtype="float32")
            norm = float(np.linalg.norm(vec))
        return (vec / norm).astype("float32")

    def embed(self, text: str) -> np.ndarray:
        self.calls.append(text)
        if self.raise_error is not None:
            raise self.raise_error
        return self._raw(text)

    @property
    def dim(self) -> int:
        return self.dim_value
