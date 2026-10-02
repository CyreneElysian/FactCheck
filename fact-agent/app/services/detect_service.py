"""
事实检测服务

流程：生成搜索词 -> 搜索 -> 抓取正文 -> 组装提示词 -> LLM 判断 -> 校验并解析结果。

时间预算：
单次 detect 受 DETECT_TOTAL_BUDGET（默认 45s）约束，各步骤按剩余时间设置请求超时；
预算耗尽则提前收尾并转人工。该预算必须小于 Java 侧读超时（fact-check.agent.timeout，默认 90s），
否则会出现"Java 已判失败、Python 仍在跑"的状态不一致。

安全：
用户文本与抓取到的网页正文均为不可信输入，统一做标签转义后放入 <untrusted_*> 标签，
并在系统提示中声明其只是数据；模型输出经强制校验，不合规一律转人工。
"""
import ipaddress
import json
import logging
import math
import re
import socket
import time
from typing import List, Optional
from urllib.parse import urljoin, urlparse

import requests

from ..config import settings
from ..prompts.detect_prompt import SYSTEM_PROMPT, USER_PROMPT_TEMPLATE
from ..schemas import DetectResponse, Evidence
from .credibility import score_source
from .llm import OpenAICompatibleLLM
from .search import SearchResult, SearchTool

logger = logging.getLogger(__name__)

# 允许的结论取值
VALID_VERDICTS = ("TRUE", "FALSE", "UNVERIFIED")

# 不可信输入中的标签字面量，转义后再拼接，避免伪造提示词边界（提示词注入）
_TAG_PATTERN = re.compile(r"</?\s*untrusted_[a-z_]+\s*>", re.IGNORECASE)

# 相关性估计：位置分与词面重合按**乘性**组合时，词面完全不重合能保留的位置分比例。
#
# 为什么不用加权求和（原实现为 0.55×位置 + 0.45×重合）：
# 加权求和会让"排在第 1 位但与被检测消息毫无词面关系"的证据拿到 0.55 分，
# 而模型自报的相关度通常偏低（0~1 且集中在小数值），两者量纲不同，
# 混排后会出现"模型明确标记为不相关（0.1）的证据，排序反而在毫不相关的检索结果之后"。
# 乘性组合后位置分单独最高只值 0.3，配合"全部证据统一用本函数打分"，
# 排序只依赖一个口径，可复现、可解释。
_RELEVANCE_FLOOR = 0.3

# ----------------------------------------------------------------------
# 抓取网页的安全约束（SSRF 防护）
#
# 被抓取的 URL 来自搜索引擎结果，属于**不可信输入**：
# 若不加限制，攻击者只要让某条结果指向 http://169.254.169.254/（云元数据）、
# http://127.0.0.1:8080/（内网服务）或任意内网地址，就能借服务端发起内网请求。
# ----------------------------------------------------------------------

# 只允许 http/https
_ALLOWED_SCHEMES = ("http", "https")

# 最多跟随的重定向次数（每一跳都要重新校验）
_MAX_REDIRECTS = 3

# 明确禁止的内网主机名
_BLOCKED_HOSTNAMES = {
    "localhost", "localhost.localdomain", "ip6-localhost", "ip6-loopback",
    "metadata", "metadata.google.internal",
}

# 禁止的内网域名后缀
_BLOCKED_HOST_SUFFIXES = (".localhost", ".local", ".internal", ".lan", ".home")


def _ip_is_blocked(ip: "ipaddress._BaseAddress") -> bool:
    """
    非"公网可路由"地址一律禁止

    用 not is_global 而不是逐个 is_private / is_loopback 判断，是因为后者会漏掉
    **既不是私网、也不可路由**的段落，例如 CGNAT `100.64.0.0/10`（实测 is_private 为 False）、
    TEST-NET `192.0.2.0/24` 等。is_global 为 False 同时覆盖了私网、回环、链路本地、
    保留、组播、未指定地址，语义也更贴近"能不能从公网访问"。
    """
    return not ip.is_global


def _host_is_blocked(host: str) -> bool:
    """
    判断主机名是否不可访问

    - 字面量 IP：直接按网段判断；
    - 域名：解析后**逐个地址**判断（防止域名指向 127.0.0.1 或内网段）；
    - 解析失败：视为不可访问（fail-closed）。
    """
    name = (host or "").strip().strip("[]").lower()
    if not name:
        return True
    if name in _BLOCKED_HOSTNAMES or name.endswith(_BLOCKED_HOST_SUFFIXES):
        return True

    try:
        return _ip_is_blocked(ipaddress.ip_address(name))
    except ValueError:
        pass

    try:
        infos = socket.getaddrinfo(name, None)
    except OSError as exc:
        logger.warning("抓取前解析主机失败，按不可访问处理：host=%s, error=%s", name, exc)
        return True
    if not infos:
        return True
    for info in infos:
        try:
            if _ip_is_blocked(ipaddress.ip_address(info[4][0])):
                return True
        except ValueError:
            continue
    return False


def _assert_url_allowed(url: str) -> None:
    """
    校验待抓取 URL 是否允许访问，不允许时抛出 ValueError

    公开给测试使用（不发起任何请求）。
    """
    parsed = urlparse(url)
    if parsed.scheme.lower() not in _ALLOWED_SCHEMES:
        raise ValueError(f"不支持的抓取协议：{parsed.scheme or '(空)'}")
    if not parsed.hostname:
        raise ValueError("URL 缺少主机名")
    if _host_is_blocked(parsed.hostname):
        raise ValueError(f"禁止抓取内网/回环/保留地址：{parsed.hostname}")



class DetectService:
    """LLM 事实检测服务"""

    def __init__(self, search_tool: SearchTool, llm: OpenAICompatibleLLM):
        self.search_tool = search_tool
        self.llm = llm

    # ------------------------------------------------------------------
    # 主流程
    # ------------------------------------------------------------------

    def detect(self, text: str) -> DetectResponse:
        """
        对文本进行事实检测

        Returns:
            DetectResponse；error=True 表示服务侧故障（应标记为"检测失败"而非转人工）
        """
        deadline = time.monotonic() + max(5, settings.DETECT_TOTAL_BUDGET)
        text = (text or "").strip()
        if not text:
            # 空内容没有任何可核查的信息：直接返回"无法判断"，不消耗任何 LLM 调用
            # （原先会白跑两次——生成搜索词 + 判定——结果是既花钱又必然判不了）
            # 用 error=False 而不是 True：这不是服务故障，不该让 Java 记成"可重试的检测失败"。
            # 图片检测在 OCR 无文字时也会走到这里，此时转人工正是期望行为。
            logger.info("待检测文本为空，直接返回无法判断（未调用 LLM）")
            return DetectResponse(
                can_judge=False,
                human_reason="待检测内容为空，无法核查",
                error=False,
            )
        if len(text) > settings.DETECT_MAX_TEXT_LENGTH:
            logger.warning("待检测文本过长（%d 字），已截断至 %d 字",
                           len(text), settings.DETECT_MAX_TEXT_LENGTH)
            text = text[: settings.DETECT_MAX_TEXT_LENGTH]
        logger.info("开始事实检测：text=%s", text[:200])

        # 1. 生成搜索词（LLM 生成，失败退回原句）
        queries = self._build_queries(text, deadline)

        # 2. 搜索
        results = self._search(queries, settings.SEARCH_TOP_K, deadline)
        logger.info("搜索到 %d 条结果", len(results))

        # 3. 抓取部分网页正文，补充证据上下文
        results = self._enrich_results(results, deadline)

        # 4. 组装提示词
        safe_text = self._sanitize_untrusted(text)
        evidence_text = self._format_evidence(results)
        prompt = USER_PROMPT_TEMPLATE.format(
            text=safe_text, evidence=evidence_text or "无搜索结果"
        )

        # 5. 调用 LLM
        messages = [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": prompt},
        ]
        remaining = self._remaining(deadline)
        if remaining <= 1:
            logger.warning("检测预算已耗尽，未调用 LLM，直接转人工")
            return DetectResponse(
                can_judge=False,
                human_reason="检测超时（超过整体时间预算），请稍后重试或转人工诊断",
                evidence=self._build_evidence(results, text),
                error=True,
            )
        try:
            llm_text = self.llm.chat(messages, timeout=min(settings.LLM_TIMEOUT, remaining))
        except Exception as exc:
            logger.error("LLM 检测调用失败：%s", exc)
            return DetectResponse(
                can_judge=False,
                human_reason=f"LLM 调用失败：{exc}",
                evidence=self._build_evidence(results, text),
                error=True,
            )

        # 6. 校验并解析 LLM 输出
        return self._parse_response(llm_text, results, text)

    # ------------------------------------------------------------------
    # 搜索词与检索
    # ------------------------------------------------------------------

    def _build_queries(self, text: str, deadline: float) -> List[str]:
        """生成搜索查询词：优先让 LLM 生成，失败或超预算则退回原句"""
        fallback = [text[:80], "事实核查 " + text[:60]]
        if not self.llm.api_key:
            return fallback
        remaining = self._remaining(deadline)
        if remaining <= 2:
            logger.warning("预算不足，跳过 LLM 生成搜索词，使用原句")
            return fallback
        try:
            prompt = (
                "你是事实核查助手，请为下面这条消息生成 1-3 个适合搜索引擎检索的查询词。"
                "每个查询词一行，不要编号，不要解释。\n消息：" + self._sanitize_untrusted(text)
            )
            raw = self.llm.chat(
                [
                    {"role": "system", "content": "你只输出搜索查询词，每行一个。"},
                    {"role": "user", "content": prompt},
                ],
                timeout=min(settings.LLM_TIMEOUT, remaining),
            )
            lines = [line.strip() for line in (raw or "").splitlines() if line.strip()]
            # 只去掉明显的编号前缀，避免把"3·15晚会"这类合法查询词误删
            lines = [re.sub(r"^\s*(?:\d{1,2}[.、)]|[-*])\s*", "", line) for line in lines]
            lines = [line for line in lines if line]
            queries = lines[:3]
            if queries:
                logger.info("LLM 生成搜索词：%s", queries)
                return queries
        except Exception as exc:
            logger.warning("LLM 生成搜索词失败，使用原句：%s", exc)
        return fallback

    def _search(self, queries: List[str], top_k: int, deadline: float) -> List[SearchResult]:
        """执行搜索并按 URL 去重"""
        seen_urls = set()
        merged: List[SearchResult] = []
        for query in queries:
            timeout = self._remaining(deadline)
            if timeout <= 1:
                logger.warning("预算不足，提前结束搜索（已完成 %d 个查询）", len(merged))
                break
            try:
                for item in self.search_tool.search(query, top_k, timeout=timeout):
                    if item.url and item.url in seen_urls:
                        continue
                    if item.url:
                        seen_urls.add(item.url)
                    merged.append(item)
            except Exception as exc:
                logger.warning("搜索失败（query=%s）：%s", query, exc)
        return merged

    def _enrich_results(self, results: List[SearchResult], deadline: float) -> List[SearchResult]:
        """抓取前 N 条结果的网页正文，写入 content（不覆盖原始 snippet）"""
        fetch_top = settings.SEARCH_FETCH_TOP
        if fetch_top <= 0:
            return results
        for item in results[:fetch_top]:
            if not item.url:
                continue
            timeout = self._remaining(deadline)
            if timeout <= 2:
                logger.info("预算不足，停止抓取网页正文")
                break
            try:
                text = self._fetch_page_text(item.url, timeout=min(settings.SEARCH_PAGE_TIMEOUT, timeout))
                if text:
                    item.content = text[: settings.SEARCH_PAGE_MAX_CHARS]
                    logger.info("已抓取网页正文：url=%s, length=%d", item.url, len(item.content))
            except Exception as exc:
                logger.warning("网页抓取失败，使用搜索摘要：url=%s, error=%s", item.url, exc)
        return results

    @staticmethod
    def _fetch_page_text(url: str, timeout: int = 10) -> str:
        """抓取网页并提取纯文本

        安全约束（SSRF 防护）：
        - 只允许 http/https；
        - 拒绝内网/回环/链路本地/保留地址（含域名解析到这些地址的情况）；
        - **不自动跟随重定向**，每一跳都重新校验，避免"公网地址 302 到内网"绕过；
        - 主机解析失败按不可访问处理（fail-closed）。
        """
        headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                          "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"
        }

        current = url
        resp = None
        for _ in range(_MAX_REDIRECTS + 1):
            _assert_url_allowed(current)
            resp = requests.get(current, headers=headers, timeout=timeout, allow_redirects=False)
            location = resp.headers.get("Location") if resp.headers else None
            if resp.status_code in (301, 302, 303, 307, 308) and location:
                current = urljoin(current, location)
                logger.info("抓取遇到重定向，校验后继续：%s -> %s", url, current)
                continue
            resp.raise_for_status()
            break
        else:
            raise ValueError(f"重定向次数过多（超过 {_MAX_REDIRECTS} 次）：{url}")

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

    # ------------------------------------------------------------------
    # 提示词组装
    # ------------------------------------------------------------------

    @staticmethod
    def _sanitize_untrusted(value: object) -> str:
        """
        转义不可信输入中的提示词边界标签字面量，防止内容伪造 </untrusted_claim> 之类边界
        """
        if value is None:
            return ""
        return _TAG_PATTERN.sub("[已过滤标签]", str(value))

    def _format_evidence(self, results: List[SearchResult]) -> str:
        """格式化证据列表供提示词使用（含来源、发布时间，正文优先于摘要）"""
        lines = []
        for i, r in enumerate(results[:10], 1):
            body = r.content or r.snippet or ""
            parts = [
                f"{i}. 标题：{self._sanitize_untrusted(r.title)}",
                f"   链接：{self._sanitize_untrusted(r.url)}",
                f"   来源：{self._sanitize_untrusted(r.source)}",
            ]
            if r.date:
                parts.append(f"   发布时间：{self._sanitize_untrusted(r.date)}")
            parts.append(f"   内容：{self._sanitize_untrusted(body)}")
            lines.append("\n".join(parts))
        return "\n".join(lines)

    # ------------------------------------------------------------------
    # 输出校验与解析
    # ------------------------------------------------------------------

    def _parse_response(self, llm_text: str, search_results: List[SearchResult],
                        claim_text: str) -> DetectResponse:
        """解析 LLM 返回的 JSON，做强制校验，并补充来源可信度与相关度"""
        data = self._extract_json(llm_text)
        if not isinstance(data, dict):
            logger.warning("LLM 输出无法解析为 JSON，标记为服务侧故障")
            return DetectResponse(
                can_judge=False,
                human_reason="LLM 输出无法解析，已标记为检测失败，可重试",
                evidence=self._build_evidence(search_results, claim_text),
                error=True,
            )

        can_judge = bool(data.get("can_judge", False))
        verdict = data.get("verdict")
        if isinstance(verdict, str):
            verdict = verdict.strip().upper()
        if verdict not in VALID_VERDICTS:
            verdict = None

        # 结论缺失或非法：模型确实没给出可用结论，转人工复核（不算服务故障）
        if can_judge and verdict is None:
            logger.warning("LLM 声称可判定但 verdict 非法，转人工：verdict=%s", data.get("verdict"))
            return DetectResponse(
                can_judge=False,
                human_reason="模型未给出合法结论（verdict 取值非法），转人工复核",
                evidence=self._build_evidence(search_results, claim_text),
                error=False,
            )

        confidence = self._coerce_confidence(data.get("confidence"))
        reasoning = data.get("reasoning_chain")
        reasoning = str(reasoning) if reasoning is not None else None
        human_reason = data.get("human_reason")
        human_reason = str(human_reason) if human_reason is not None else None

        evidence = self._parse_evidence(data.get("evidence"), claim_text, search_results)

        if not can_judge:
            # can_judge=false 时不应带结论与置信度
            verdict = None
            confidence = None
            if not human_reason:
                human_reason = "AI 无法判断，转人工诊断"

        return DetectResponse(
            can_judge=can_judge,
            verdict=verdict,
            confidence=confidence,
            reasoning_chain=reasoning,
            evidence=evidence,
            human_reason=human_reason,
            error=False,
        )

    def _parse_evidence(self, raw_evidence: object, claim_text: str,
                        search_results: List[SearchResult]) -> List[Evidence]:
        """合并"模型给出的证据"与"检索到的结果"，并按相关度降序返回

        设计要点（由离线单测暴露的问题驱动）：
        - 模型给出的证据优先（它读过正文，相关性判断更准），但**不再整体丢弃检索结果**——
          否则检索到的权威来源会因为模型没提到而完全消失，管理员也就看不到。
        - 按 URL 去重：同一 URL 以模型给出的文本为准，检索结果只做补充。
        - title/url/snippet 全空的条目直接丢弃，避免空行顶掉真实证据。
        - 合并后最多保留 10 条（与提示词中给模型的条数一致）。

        **相关度只用一个口径**：`_estimate_relevance`（位置分 × 词面重合，乘性）。
        模型自报的 `relevance_score` 只记入 debug 日志、**不参与排序，也不写进结果**，
        理由与 `source_credibility` 相同——模型自报的数字无法复核，且它与本项目的
        启发式分数不在同一量纲上，混排会产生"模型标 0.1 的不相关证据排在毫无词面关系的
        检索结果之后"这类不可解释的顺序（见 P5-67）。
        """
        merged: List[Evidence] = []
        seen_urls = set()

        def _key(url: str) -> str:
            return (url or "").strip().rstrip("/").lower()

        if isinstance(raw_evidence, list):
            for item in raw_evidence:
                if not isinstance(item, dict):
                    logger.warning("跳过非法证据条目（非对象）：%s", type(item).__name__)
                    continue
                title = str(item.get("title") or "").strip()
                url = str(item.get("url") or "").strip()
                snippet = str(item.get("snippet") or "").strip()
                if not (title or url or snippet):
                    logger.warning("跳过空证据条目（title/url/snippet 均为空）")
                    continue
                key = _key(url)
                if key and key in seen_urls:
                    continue
                if key:
                    seen_urls.add(key)

                rule_score = score_source(url)
                model_score = self._coerce_unit(item.get("source_credibility"), None)
                if model_score is not None and abs(model_score - rule_score) > 0.01:
                    # 可信度以确定性域名规则为准：模型自报的数字无法复核，不予采信
                    logger.debug("忽略模型自报的来源可信度：url=%s, model=%s, rule=%s",
                                 url, model_score, rule_score)
                model_relevance = self._coerce_unit(item.get("relevance_score"), None)
                if model_relevance is not None:
                    logger.debug("忽略模型自报的相关度（统一改用确定性口径）：url=%s, model=%s",
                                 url, model_relevance)
                merged.append(Evidence(
                    title=title,
                    url=url,
                    snippet=snippet,
                    source=str(item.get("source") or "").strip(),
                    source_credibility=rule_score,
                    # 留空，稍后由 _estimate_relevance 统一打分，保证全表同一量纲
                    relevance_score=None,
                ))

        # 用检索结果补充模型未提及的条目（权威来源不会因模型忽略而丢失）
        for rank, r in enumerate(search_results):
            if len(merged) >= 10:
                break
            key = _key(r.url)
            if key and key in seen_urls:
                continue
            if key:
                seen_urls.add(key)
            body = r.content or r.snippet or ""
            merged.append(Evidence(
                title=r.title,
                url=r.url,
                snippet=r.snippet,
                source=r.source,
                source_credibility=score_source(r.url),
                relevance_score=self._estimate_relevance(claim_text, r.title, body, rank),
            ))

        if not merged:
            return self._build_evidence(search_results, claim_text)

        # 统一打分：模型给的条目此刻都是空值，检索结果已有分也一并重算，
        # 避免"先按检索排名打分、后按合并后位置打分"两个位置口径混在一起
        for rank, ev in enumerate(merged):
            ev.relevance_score = self._estimate_relevance(
                claim_text, ev.title, ev.snippet, rank
            )
        merged.sort(key=lambda e: e.relevance_score or 0.0, reverse=True)
        return merged[:10]

    def _build_evidence(self, results: List[SearchResult], claim_text: str) -> List[Evidence]:
        """将搜索结果转换为 Evidence 列表（按相关度降序）"""
        evidence = []
        for rank, r in enumerate(results[:10]):
            body = r.content or r.snippet or ""
            evidence.append(Evidence(
                title=r.title,
                url=r.url,
                snippet=r.snippet,
                source=r.source,
                source_credibility=score_source(r.url),
                relevance_score=self._estimate_relevance(claim_text, r.title, body, rank),
            ))
        evidence.sort(key=lambda e: e.relevance_score or 0.0, reverse=True)
        return evidence

    # ------------------------------------------------------------------
    # 工具方法
    # ------------------------------------------------------------------

    @staticmethod
    def _remaining(deadline: float) -> int:
        """剩余预算（秒，向上取整，最小 0）

        向上取整而不是向下：剩余 1.9s 时应给 2s，"还剩不到 1 秒就整段放弃"会白扔预算。
        """
        return max(0, math.ceil(deadline - time.monotonic()))

    @staticmethod
    def _coerce_confidence(value: object) -> Optional[float]:
        """置信度归一到 [0,1]，非法值返回 None"""
        try:
            num = float(value)  # type: ignore[arg-type]
        except (TypeError, ValueError):
            return None
        return round(min(1.0, max(0.0, num)), 4)

    @staticmethod
    def _coerce_unit(value: object, default: Optional[float]) -> Optional[float]:
        """把 0-1 区间的字段归一；非法值回落到 default"""
        try:
            num = float(value)  # type: ignore[arg-type]
        except (TypeError, ValueError):
            return default
        return round(min(1.0, max(0.0, num)), 4)

    @staticmethod
    def _bigrams(text: str) -> set:
        """提取字符二元组，用于中文词面重合估计（去掉空白与标点）"""
        cleaned = re.sub(r"[\s\W_]+", "", text or "", flags=re.UNICODE)
        if len(cleaned) < 2:
            return set()
        return {cleaned[i:i + 2] for i in range(len(cleaned) - 1)}

    def _estimate_relevance(self, claim: str, title: str, body: str, rank: int) -> float:
        """
        估计证据相关度（0-1）：位置分与词面重合的**乘性**组合

        - 位置分：越靠前越高，rank 0 -> 1.0，之后按 0.08 递减
        - 词面重合：待检测消息与（标题+内容）的字符二元组重合率
        - 组合：`位置分 × (0.3 + 0.7 × 重合率)`

        乘性组合保证"排得靠前"不会单独换来中高分：完全不重合时最多 0.3，
        必须真的有词面关系才能拿到更高分。这个口径被全项目统一使用
        （见 `_parse_evidence` 中"不再采信模型自报 relevance_score"的说明）。
        """
        position = max(0.0, 1.0 - 0.08 * rank)
        claim_grams = self._bigrams(claim)
        if not claim_grams:
            overlap = 0.0
        else:
            evidence_grams = self._bigrams(f"{title} {body}")
            overlap = len(claim_grams & evidence_grams) / len(claim_grams)
        score = position * (_RELEVANCE_FLOOR + (1 - _RELEVANCE_FLOOR) * min(1.0, overlap))
        return round(min(1.0, max(0.0, score)), 4)

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
