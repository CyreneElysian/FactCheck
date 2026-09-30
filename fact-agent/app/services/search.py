"""
搜索源抽象与实现

内置：
- DummySearchTool：不联网，返回空结果，便于本地联调
- TavilySearchTool：调用 Tavily Search API，需要 SEARCH_API_KEY
- SerpApiTool：调用 SerpApi，需要 SEARCH_API_KEY
- BochaSearchTool：调用博查 Web Search API，需要 SEARCH_API_KEY

后续可扩展 Bing 等，只需继承 SearchTool 并注册到工厂方法。

约定：
- `snippet` 保留搜索源返回的原始摘要；抓取到的网页正文放在 `content`，
  两者不互相覆盖（改写前会把正文直接覆盖 snippet，导致原始摘要丢失）。
- `search()` 接受 timeout 参数，由调用方按整体预算传入剩余时间。
"""
import logging
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import List, Optional
from urllib.parse import urlparse

import requests

logger = logging.getLogger(__name__)


def _host(url: str) -> str:
    """从 URL 提取站点名，作为来源展示（搜索源未提供来源时的兜底）"""
    try:
        return urlparse(url).netloc.lower()
    except Exception:
        return ""


@dataclass
class SearchResult:
    """搜索结果"""
    title: str = ""
    url: str = ""
    snippet: str = ""
    source: str = ""
    date: str = ""
    # 抓取到的网页正文（可选，见 detect_service._enrich_results）
    content: str = ""


class SearchTool(ABC):
    """搜索源抽象"""

    @abstractmethod
    def search(self, query: str, top_k: int = 10, timeout: int = 20) -> List[SearchResult]:
        """执行搜索"""


class DummySearchTool(SearchTool):
    """空实现，不发起网络请求"""

    def search(self, query: str, top_k: int = 10, timeout: int = 20) -> List[SearchResult]:
        logger.info("DummySearchTool 未配置真实搜索源，返回空结果（query=%s）", query)
        return []


class TavilySearchTool(SearchTool):
    """Tavily Search API"""

    URL = "https://api.tavily.com/search"

    def __init__(self, api_key: str):
        self.api_key = api_key

    def search(self, query: str, top_k: int = 10, timeout: int = 20) -> List[SearchResult]:
        resp = requests.post(
            self.URL,
            json={"api_key": self.api_key, "query": query, "max_results": top_k},
            timeout=timeout,
        )
        resp.raise_for_status()
        data = resp.json()
        results: List[SearchResult] = []
        for item in data.get("results", [])[:top_k]:
            url = item.get("url", "")
            results.append(SearchResult(
                title=item.get("title", ""),
                url=url,
                snippet=item.get("content", ""),
                source=_host(url),
                # Tavily 部分结果带发布时间
                date=item.get("published_date", "") or "",
            ))
        return results


class SerpApiTool(SearchTool):
    """SerpApi Search API"""

    URL = "https://serpapi.com/search.json"

    def __init__(self, api_key: str):
        self.api_key = api_key

    def search(self, query: str, top_k: int = 10, timeout: int = 20) -> List[SearchResult]:
        resp = requests.get(
            self.URL,
            params={"q": query, "api_key": self.api_key, "num": top_k},
            timeout=timeout,
        )
        resp.raise_for_status()
        data = resp.json()
        results: List[SearchResult] = []
        for item in data.get("organic_results", [])[:top_k]:
            url = item.get("link", "")
            results.append(SearchResult(
                title=item.get("title", ""),
                url=url,
                snippet=item.get("snippet", ""),
                source=item.get("source", "") or _host(url),
                date=item.get("date", "") or "",
            ))
        return results


class BochaSearchTool(SearchTool):
    """博查 Web Search API"""

    URL = "https://api.bochaai.com/v1/web-search"

    def __init__(self, api_key: str):
        self.api_key = api_key

    def search(self, query: str, top_k: int = 10, timeout: int = 20) -> List[SearchResult]:
        resp = requests.post(
            self.URL,
            headers={"Authorization": f"Bearer {self.api_key}"},
            json={"query": query, "count": top_k},
            timeout=timeout,
        )
        resp.raise_for_status()
        data = resp.json()
        results: List[SearchResult] = []
        pages = data.get("data", {}).get("webPages", [])
        for item in pages[:top_k]:
            url = item.get("url", "")
            results.append(SearchResult(
                title=item.get("name", ""),
                url=url,
                snippet=item.get("snippet", ""),
                source=item.get("siteName", "") or _host(url),
                date=item.get("datePublished", "") or "",
            ))
        return results


def get_search_tool(name: str, api_key: str) -> SearchTool:
    """
    搜索源工厂方法
    """
    name = (name or "dummy").lower()
    if name == "tavily":
        return TavilySearchTool(api_key)
    if name == "serpapi":
        return SerpApiTool(api_key)
    if name == "bocha":
        return BochaSearchTool(api_key)
    if name == "dummy":
        return DummySearchTool()
    logger.warning("未知搜索源 %s，使用 DummySearchTool", name)
    return DummySearchTool()


def is_offline_tool(tool: object) -> bool:
    """判断当前搜索源是否不联网（用于启动告警）"""
    return isinstance(tool, DummySearchTool)


def describe_tool(tool: object) -> str:
    """返回搜索源名称，便于日志与 /health 展示"""
    return type(tool).__name__ if tool is not None else "None"


def validate_tool_config(name: str, api_key: str) -> Optional[str]:
    """
    校验搜索源配置，返回错误说明（无问题时返回 None）

    联网搜索源缺少 API Key 时，宁可启动即告警，也不要等到检测时静默退化为纯模型知识。
    """
    name = (name or "dummy").lower()
    if name == "dummy":
        return None
    if name not in ("tavily", "serpapi", "bocha"):
        return f"未知搜索源 {name}，已退化为 dummy（不联网）"
    if not api_key:
        return f"SEARCH_TOOL={name} 但未配置 SEARCH_API_KEY，联网检索将失败"
    return None
