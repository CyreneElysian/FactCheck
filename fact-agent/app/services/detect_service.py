"""
事实检测服务

流程：生成搜索词 -> 搜索 -> 组装提示词 -> LLM 判断 -> 解析 JSON 结果。
"""
import json
import logging
import re
from typing import List

import requests

from ..config import settings
from ..prompts.detect_prompt import SYSTEM_PROMPT, USER_PROMPT_TEMPLATE
from ..schemas import DetectResponse, Evidence
from .credibility import score_source
from .llm import OpenAICompatibleLLM
from .search import SearchResult, SearchTool

logger = logging.getLogger(__name__)


class DetectService:
    """LLM 事实检测服务"""

    def __init__(self, search_tool: SearchTool, llm: OpenAICompatibleLLM):
        self.search_tool = search_tool
        self.llm = llm

    def detect(self, text: str) -> DetectResponse:
        """
        对文本进行事实检测
        """
        text = (text or "").strip()
        logger.info("开始事实检测：text=%s", text[:200])

        # 1. 生成搜索词（让 LLM 生成多个查询词，失败则退回原句）
        queries = self._build_queries(text)

        # 2. 搜索
        results = self._search(queries, settings.SEARCH_TOP_K)
        logger.info("搜索到 %d 条结果", len(results))

        # 2.1 抓取部分网页正文，补充证据上下文
        results = self._enrich_results(results)

        # 3. 组装提示词
        evidence_text = self._format_evidence(results)
        prompt = USER_PROMPT_TEMPLATE.format(text=text, evidence=evidence_text or "无搜索结果")

        # 4. 调用 LLM
        messages = [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": prompt},
        ]
        try:
            llm_text = self.llm.chat(messages)
        except Exception as exc:
            logger.error("LLM 检测调用失败：%s", exc)
            return DetectResponse(
                can_judge=False,
                human_reason=f"LLM 调用失败：{exc}",
                evidence=self._build_evidence(results),
            )

        # 5. 解析 LLM 输出
        return self._parse_response(llm_text, results)

    def _build_queries(self, text: str) -> List[str]:
        """生成搜索查询词：优先让 LLM 生成，失败则退回原句"""
        fallback = [text[:80], "事实核查 " + text[:60]]
        if not self.llm.api_key:
            return fallback
        try:
            prompt = (
                "你是事实核查助手，请为下面这条消息生成 1-3 个适合搜索引擎检索的查询词。"
                "每个查询词一行，不要编号，不要解释。" + chr(10) + "消息：" + text
            )
            raw = self.llm.chat([
                {"role": "system", "content": "你只输出搜索查询词，每行一个。"},
                {"role": "user", "content": prompt},
            ])
            lines = [line.strip() for line in raw.splitlines() if line.strip()]
            lines = [line for line in lines if not line.startswith(("1", "2", "3", "-", "查询"))]
            queries = lines[:3]
            if queries:
                logger.info("LLM 生成搜索词：%s", queries)
                return queries
        except Exception as exc:
            logger.warning("LLM 生成搜索词失败，使用原句：%s", exc)
        return fallback

    def _search(self, queries: List[str], top_k: int) -> List[SearchResult]:
        """执行搜索并去重（按 URL）"""
        seen_urls = set()
        merged: List[SearchResult] = []
        for query in queries:
            try:
                for item in self.search_tool.search(query, top_k):
                    if item.url and item.url in seen_urls:
                        continue
                    if item.url:
                        seen_urls.add(item.url)
                    merged.append(item)
            except Exception as exc:
                logger.warning("搜索失败（query=%s）：%s", query, exc)
        return merged

    def _enrich_results(self, results: List[SearchResult]) -> List[SearchResult]:
        """抓取前 N 条结果的网页正文，用于补充证据"""
        fetch_top = settings.SEARCH_FETCH_TOP
        if fetch_top <= 0:
            return results
        for item in results[:fetch_top]:
            if not item.url:
                continue
            try:
                text = self._fetch_page_text(item.url)
                if text:
                    # 用网页正文替换/补充 snippet，限制长度避免提示词过长
                    item.snippet = text[:1200]
                    logger.info("已抓取网页正文：url=%s, length=%d", item.url, len(item.snippet))
            except Exception as exc:
                logger.warning("网页抓取失败，使用搜索摘要：url=%s, error=%s", item.url, exc)
        return results

    @staticmethod
    def _fetch_page_text(url: str, timeout: int | None = None) -> str:
        """抓取网页并提取纯文本"""
        timeout = timeout or settings.SEARCH_PAGE_TIMEOUT
        headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                          "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"
        }
        resp = requests.get(url, headers=headers, timeout=timeout)
        resp.raise_for_status()
        html = resp.text
        # 去掉 script/style/noscript 等标签及其内容
        html = re.sub(r"(?is)<(script|style|noscript|header|footer|nav)\b[^>]*>.*?</(script|style|noscript|header|footer|nav)>", " ", html)
        # 去掉所有 HTML 标签
        text = re.sub(r"(?s)<[^>]+>", " ", html)
        # 常见 HTML 实体
        text = text.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        # 压缩空白
        text = re.sub(r"\s+", " ", text).strip()
        return text

    def _format_evidence(self, results: List[SearchResult]) -> str:
        """格式化证据列表供提示词使用"""
        lines = []
        for i, r in enumerate(results[:10], 1):
            lines.append(f"{i}. 标题：{r.title}\n   链接：{r.url}\n   摘要：{r.snippet}\n   来源：{r.source}")
        return "\n".join(lines)

    def _parse_response(self, llm_text: str, search_results: List[SearchResult]) -> DetectResponse:
        """解析 LLM 返回的 JSON，并补充来源可信度"""
        data = self._extract_json(llm_text)
        if data is None:
            return DetectResponse(
                can_judge=False,
                human_reason="LLM 输出无法解析，需要人工诊断",
                evidence=self._build_evidence(search_results),
            )

        evidence = []
        for item in data.get("evidence") or []:
            url = item.get("url", "")
            if not item.get("source_credibility"):
                item["source_credibility"] = score_source(url)
            evidence.append(Evidence(**item))

        # 如果 LLM 给的证据为空，回填搜索结果
        if not evidence and search_results:
            evidence = self._build_evidence(search_results)

        return DetectResponse(
            can_judge=bool(data.get("can_judge", False)),
            verdict=data.get("verdict"),
            confidence=data.get("confidence"),
            reasoning_chain=data.get("reasoning_chain"),
            evidence=evidence,
            human_reason=data.get("human_reason"),
        )

    def _build_evidence(self, results: List[SearchResult]) -> List[Evidence]:
        """将搜索结果转换为 Evidence 列表"""
        return [
            Evidence(
                title=r.title,
                url=r.url,
                snippet=r.snippet,
                source=r.source,
                source_credibility=score_source(r.url),
                relevance_score=0.5,
            )
            for r in results[:10]
        ]

    @staticmethod
    def _extract_json(text: str):
        """从 LLM 输出中提取 JSON 对象"""
        if not text:
            return None
        # 去掉 ```json ... ``` 代码块
        text = re.sub(r"```(?:json)?", "", text).replace("```", "").strip()
        try:
            return json.loads(text)
        except json.JSONDecodeError:
            # 尝试截取第一个 { 到最后一个 }
            start = text.find("{")
            end = text.rfind("}")
            if start != -1 and end != -1 and end > start:
                try:
                    return json.loads(text[start:end + 1])
                except json.JSONDecodeError:
                    return None
        return None
