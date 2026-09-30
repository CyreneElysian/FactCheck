"""
搜索源抽象与实现

内置：
- DummySearchTool：不联网，返回空结果，便于本地联调
- TavilySearchTool：调用 Tavily Search API，需要 SEARCH_API_KEY
- SerpApiTool：调用 SerpApi，需要 SEARCH_API_KEY
- BochaSearchTool：调用博查 Web Search API，需要 SEARCH_API_KEY

后续可扩展 Bing 等，只需继承 SearchTool 并注册到工厂方法。
"""
import logging
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import List

import requests

logger = logging.getLogger(__name__)


@dataclass
class SearchResult:
    """搜索结果"""
    title: str = ""
    url: str = ""
    snippet: str = ""
    source: str = ""
    date: str = ""


class SearchTool(ABC):
    """搜索源抽象"""

    @abstractmethod
    def search(self, query: str, top_k: int = 5) -> List[SearchResult]:
        """执行搜索"""


class DummySearchTool(SearchTool):
    """空实现，不发起网络请求"""

    def search(self, query: str, top_k: int = 5) -> List[SearchResult]:
        logger.info("DummySearchTool 未配置真实搜索源，返回空结果（query=%s）", query)
        return []


class TavilySearchTool(SearchTool):
    """Tavily Search API"""

    URL = "https://api.tavily.com/search"

    def __init__(self, api_key: str):
        self.api_key = api_key

    def search(self, query: str, top_k: int = 5) -> List[SearchResult]:
        resp = requests.post(
            self.URL,
            json={"api_key": self.api_key, "query": query, "max_results": top_k},
            timeout=20,
        )
        resp.raise_for_status()
        data = resp.json()
        results: List[SearchResult] = []
        for item in data.get("results", [])[:top_k]:
            results.append(SearchResult(
                title=item.get("title", ""),
                url=item.get("url", ""),
                snippet=item.get("content", ""),
                source=item.get("url", ""),
            ))
        return results


class SerpApiTool(SearchTool):
    """SerpApi Search API"""

    URL = "https://serpapi.com/search.json"

    def __init__(self, api_key: str):
        self.api_key = api_key

    def search(self, query: str, top_k: int = 5) -> List[SearchResult]:
        resp = requests.get(
            self.URL,
            params={"q": query, "api_key": self.api_key, "num": top_k},
            timeout=20,
        )
        resp.raise_for_status()
        data = resp.json()
        results: List[SearchResult] = []
        for item in data.get("organic_results", [])[:top_k]:
            results.append(SearchResult(
                title=item.get("title", ""),
                url=item.get("link", ""),
                snippet=item.get("snippet", ""),
                source=item.get("source", ""),
                date=item.get("date", ""),
            ))
        return results


class BochaSearchTool(SearchTool):
    """博查 Web Search API"""

    URL = "https://api.bochaai.com/v1/web-search"

    def __init__(self, api_key: str):
        self.api_key = api_key

    def search(self, query: str, top_k: int = 5) -> List[SearchResult]:
        resp = requests.post(
            self.URL,
            headers={"Authorization": f"Bearer {self.api_key}"},
            json={"query": query, "count": top_k},
            timeout=20,
        )
        resp.raise_for_status()
        data = resp.json()
        results: List[SearchResult] = []
        pages = data.get("data", {}).get("webPages", [])
        for item in pages[:top_k]:
            results.append(SearchResult(
                title=item.get("name", ""),
                url=item.get("url", ""),
                snippet=item.get("snippet", ""),
                source=item.get("siteName", ""),
                date=item.get("datePublished", ""),
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
