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
from ..prompts.detect_prompt import SYSTEM_PROMPT, USER_PROMPT_TEMPLATE, build_system_prompt
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
# 可预期的外部调用异常（P5-73）
#
# 这些 helper 里"降级继续"的分支只应捕获**可预期的失败**：网络/服务不可用、上游返回非法值。
# 早期一律写 except Exception，会把 AttributeError / TypeError / NameError 这类**编程错误**
# 也吞成一条 WARNING —— 代码写错了却表现成"搜索偶尔失败"，非常难查。
#
# RuntimeError 也归入"可预期"：上层封装（llm.py、HTTP 工具、requests 包装）普遍把调用失败
# 统一包成 RuntimeError；真正要放行上去的是 AttributeError / TypeError / NameError / IndexError
# 这些明确的编码错误——它们会冒泡到 detect() 顶层，记完整堆栈并返回 error=True（Java 记为检测失败）。
# ----------------------------------------------------------------------
_EXPECTED_LLM_ERRORS = (RuntimeError, ValueError, KeyError)
_EXPECTED_SEARCH_ERRORS = (requests.RequestException, RuntimeError, ValueError, KeyError)
_EXPECTED_FETCH_ERRORS = (requests.RequestException, RuntimeError, ValueError, KeyError, OSError)

# 检测总预算下限：低于该值整条链路来不及走完。配置过小时**显式告警**而不是静默改写
_MIN_DETECT_BUDGET = 5

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

# 排名之外、因"标题疑似乱码"而额外抓取的条数上限。
# 乱码标题靠排名是抓不到的（实测它在第 10 位），所以必须有这条通道；
# 但也不能无限抓——每个抓取都是一次真实网络请求，会吃掉检测预算。
_EXTRA_FETCH_FOR_BAD_TITLE = 3

# 单次检测里抓取网页的总数硬上限（排名内 + 额外），防止预算被耗尽
_MAX_TOTAL_FETCH = 6

# requests 在响应头没有 charset 时按 RFC 2616 塞的兜底编码。
# 它们不代表页面真实编码，不能当作"显式声明"采信（否则中文站点会乱码）。
_FALLBACK_ENCODINGS = {"iso-8859-1", "latin-1", "latin1", "ascii", "cp1252", "us-ascii"}

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



def _decode_response(resp: "requests.Response") -> str:
    """
    按网页**实际编码**解码响应体

    为什么不能直接用 `resp.text`（真实踩到的缺陷）：
        `requests` 在响应头没有明确 charset 时，对 text/* 会按 **ISO-8859-1** 兜底
        （RFC 2616 的历史遗留）。而国内不少站点 —— 例如人民网的部分页面 ——
        是 **GBK** 编码且未在头部声明 charset，于是中文全部变成乱码，
        再被当成"网页正文"写进证据、喂给 LLM。
        后果很隐蔽：**评分最高的权威源（health.people.com.cn 命中 0.95 域名规则）
        反而以乱码形式出现在提示词里**，模型完全读不到那篇权威报道说了什么。

    判定顺序：
        1. 仅当响应头**显式声明**了非兜底 charset 时才采信（明示优先于猜测）；
        2. 否则交给 `charset_normalizer` 做统计推断（中文页面识别率很高）；
        3. 该库缺失时退回 `requests` 自带的 `apparent_encoding`；
        4. 全部失败则按 UTF-8 容错解码（`errors="replace"`）——**永不抛异常**，
           拿不准编码不该让整次检测失败。
    """
    raw = resp.content or b""
    try:
        declared = resp.encoding
    except Exception:
        declared = None

    # 1) 头部显式声明（requests 无声明时会塞这些兜底值，不能当"明示"）
    if declared and declared.lower().replace("_", "-") not in _FALLBACK_ENCODINGS:
        try:
            return raw.decode(declared, errors="replace")
        except (LookupError, TypeError):
            pass

    # 2) 统计推断
    try:
        from charset_normalizer import from_bytes

        best = from_bytes(raw).best()
        if best and best.encoding:
            return str(best)
    except Exception:
        pass

    # 3) requests 的兜底推断
    try:
        apparent = resp.apparent_encoding
        if apparent:
            return raw.decode(apparent, errors="replace")
    except Exception:
        pass

    # 4) 最终兜底
    return raw.decode("utf-8", errors="replace")


def _is_unusual_letter(ch: str) -> bool:
    """
    该字符是否属于"中文文本里不该出现的字母系统"。

    实测教训：乱码 `רңҹ2A° ˯ǱƷݳƷ----` 里是**希伯来字母 ר(U+05E8)** 与
    **西里尔字母 ң(U+04A3)**，它们都**不在** U+0080–U+00FF 区间
    （那是 latin-1 补充区）。所以"高位 latin 字符数"这个判据会漏判——
    识别乱码必须看**更宽的 Unicode 区块**。
    """
    code = ord(ch)
    return (
        0x0400 <= code <= 0x052F        # 西里尔文 / 西里尔补充
        or 0x0530 <= code <= 0x058F     # 亚美尼亚文
        or 0x0590 <= code <= 0x05FF     # 希伯来文
        or 0x0600 <= code <= 0x06FF     # 阿拉伯文
        or 0x0700 <= code <= 0x074F     # 叙利亚文
        or 0x0780 <= code <= 0x07BF     # 塔纳文
        or 0x0080 <= code <= 0x024F     # latin-1 补充 + 拉丁扩展 A/B
    )


def _looks_mojibake(value: str) -> bool:
    """
    判断字符串是否疑似"中文被误读"的乱码。

    <h3>判据为什么是"比例"而不是"有没有汉字"</h3>
    最初写成"有汉字就算正常"，实测**直接漏判**：Tavily 返回的那段坏摘要
    `ݡ桷2022й̬ݿ⣬о2022ڵ˯״ʾ...` 里恰好混进了 2 个形似汉字的字符
    （`桷` U+6877），于是被判成"正常中文"。

    正确的判据是**非 ASCII 字符里汉字的占比**：
    <ul>
      <li>正常中文：非 ASCII 字符几乎都是汉字，占比 &gt; 0.7；</li>
      <li>误读乱码：非 ASCII 字符散落在西里尔/希伯来/阿拉伯补充/盲文等区块，
          汉字占比极低（实测那段是 2/50 ≈ 0.04）。</li>
    </ul>

    <h3>真实来源</h3>
    实测 Tavily 对 `health.people.com.cn`（gb2312 页面）返回的 title/snippet
    本身就是乱码，而我们**自己抓同一个页面是干净的**（`_decode_response` 已修好）。
    所以正确做法不是"还原"这个坏字符串——它已双重损坏、不可逆
    （混着被 UTF-8 化的单字节，无法编回 latin-1）——而是**换成我们抓到的真实标题**，
    见 `_enrich_results`。
    """
    if not value:
        return False
    # 纯 ASCII（英文/数字标题）没有可判据的非 ASCII 字符，不算乱码
    non_ascii = [ch for ch in value if ord(ch) > 127]
    if len(non_ascii) < 3:
        return False

    cjk = sum(1 for ch in non_ascii if "\u4e00" <= ch <= "\u9fff")
    ratio = cjk / len(non_ascii)
    if ratio < 0.5:
        return True
    # 中文占比够高，但仍夹杂异常字母系统的字符 → 也视为可疑
    unusual = sum(1 for ch in non_ascii if _is_unusual_letter(ch))
    return unusual >= 2 and ratio < 0.9


def _extract_html_title(html: str) -> str:
    """从 HTML 中提取 `<title>` 文本（找不到返回空串）"""
    match = re.search(r"(?is)<title[^>]*>(.*?)</title>", html)
    if not match:
        return ""
    title = re.sub(r"\s+", " ", match.group(1)).strip()
    return (title.replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">"))


def _repair_mojibake(value: str, where: str) -> str:
    """
    尝试修复"中文被按单字节编码误读"造成的乱码；修不了就原样返回。

    典型可修复形态（搜索引擎/上游接口偶尔会返回这种）：
        "专家" 的 UTF-8 字节 E4 B8 93 E5 AE B6 被 latin-1 逐字节读成 "ä¸\x93å®¶"
    判据：文本里**几乎没有汉字**，却有一堆 U+0080–U+00FF 区间的字符。

    修复方式：把它按 latin-1 编回字节，再依次尝试常见中文编码解码，
    取汉字最多的结果（要求确实变好才采纳）。

    ⚠️ 注意**不能**指望它救回所有乱码。实测 recordId=27 那条
    `t_evidence.title` 是"双重损坏"（原始 GBK 字节里混进了被 UTF-8 化的单字节，
    如 C2 B0 / C2 A0），信息已不可逆。所以这只是兜底：
    真正的修复必须在**产生乱码的那一环之前**，而这里同时打 WARN 便于定位来源。
    """
    if not value:
        return value
    cjk = sum(1 for ch in value if "\u4e00" <= ch <= "\u9fff")
    high_latin = sum(1 for ch in value if "\u0080" <= ch <= "\u00ff")
    # 有明显汉字、或没有可疑高位字符 → 认为正常
    if cjk > 0 or high_latin < 3:
        return value

    try:
        raw = value.encode("latin-1")
    except UnicodeEncodeError:
        return value

    best, best_cjk = None, cjk
    for enc in ("utf-8", "gb18030", "gbk", "big5"):
        try:
            candidate = raw.decode(enc)
        except (UnicodeDecodeError, LookupError):
            continue
        got = sum(1 for ch in candidate if "\u4e00" <= ch <= "\u9fff")
        if got > best_cjk:
            best, best_cjk = candidate, got

    if best is None:
        logger.warning("疑似乱码但无法修复（where=%s）：%s", where, value[:60])
        return value

    logger.warning("已修复乱码（where=%s）：%r -> %r", where, value[:40], best[:40])
    return best


class DetectService:
    """LLM 事实检测服务"""

    def __init__(self, search_tool: SearchTool, llm: OpenAICompatibleLLM):
        self.search_tool = search_tool
        self.llm = llm

    # ------------------------------------------------------------------
    # 主流程
    # ------------------------------------------------------------------

    def detect(self, text: str, category: str | None = None) -> DetectResponse:
        """
        对文本进行事实检测

        Args:
            text: 待核查原文
            category: 消息分类（前端"消息分类"：其他/健康/社会/财经/科技/政策）。
                **可选**；有对应指导时会在系统提示词末尾追加该分类的核查注意事项。
                未知分类/为空一律退回通用提示词，不影响检测能否进行。

        Returns:
            DetectResponse；error=True 表示服务侧故障（应标记为"检测失败"而非转人工）
        """
        budget = settings.DETECT_TOTAL_BUDGET
        if budget < _MIN_DETECT_BUDGET:
            # 显式告警：早期是 max(5, budget) 直接静默改写，配置写错（例如 0.5）时看不出任何异常
            logger.error("DETECT_TOTAL_BUDGET=%s 小于下限 %ds，已按 %ds 执行；请修正配置",
                         budget, _MIN_DETECT_BUDGET, _MIN_DETECT_BUDGET)
            budget = _MIN_DETECT_BUDGET
        deadline = time.monotonic() + budget
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
        # 系统提示词 = 通用提示词 + （若有）分类专属注意事项。
        # 分类指导必须放在 system message 里：它是**我们的指令**，不是不可信数据；
        # 混进 user 消息会破坏"不可信内容一律包在 <untrusted_*> 标签内"的边界约定。
        system_prompt = build_system_prompt(category)
        if category and system_prompt != SYSTEM_PROMPT:
            logger.info("已追加分类专属核查注意事项：category=%s（系统提示词 %d -> %d 字）",
                        category, len(SYSTEM_PROMPT), len(system_prompt))
        elif category:
            # 前端传了分类但我们没有对应指导：记一条日志，
            # 便于发现"前后端分类清单不一致"，而不是静默忽略
            logger.info("分类无专属提示，使用通用提示词：category=%s", category)

        messages = [
            {"role": "system", "content": system_prompt},
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
            llm_text = self.llm.chat(messages, timeout=min(settings.LLM_TIMEOUT, remaining),
                                     deadline=deadline)
        except _EXPECTED_LLM_ERRORS as exc:
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
                deadline=deadline,
            )
            lines = [line.strip() for line in (raw or "").splitlines() if line.strip()]
            # 只去掉明显的编号前缀，避免把"3·15晚会"这类合法查询词误删
            lines = [re.sub(r"^\s*(?:\d{1,2}[.、)]|[-*])\s*", "", line) for line in lines]
            lines = [line for line in lines if line]
            queries = lines[:3]
            if queries:
                logger.info("LLM 生成搜索词：%s", queries)
                return queries
        except _EXPECTED_LLM_ERRORS as exc:
            logger.warning("LLM 生成搜索词失败，使用原句：%s", exc)
        return fallback

    def _search(self, queries: List[str], top_k: int, deadline: float) -> List[SearchResult]:
        """执行搜索并按 URL 去重"""
        seen_urls = set()
        merged: List[SearchResult] = []
        for query in queries:
            # 单次搜索超时取三者的最小值：配置上限（SEARCH_TIMEOUT，运维真正想调的那个）、
            # 整体预算剩余时间。此前 SEARCH_TIMEOUT 只是"定义了但从不读取"的配置项，
            # 按 README 调它完全不生效且没有任何提示 —— 现在接上。
            timeout = min(settings.SEARCH_TIMEOUT, self._remaining(deadline))
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
            except _EXPECTED_SEARCH_ERRORS as exc:
                logger.warning("搜索失败（query=%s）：%s", query, exc)
        return merged

    def _enrich_results(self, results: List[SearchResult], deadline: float) -> List[SearchResult]:
        """抓取网页正文写入 content（不覆盖原始 snippet），并修掉搜索接口给出的乱码标题

        <h3>为什么要抓"排名靠后但标题可疑"的条目</h3>
        原先只抓前 {@code SEARCH_FETCH_TOP}（默认 3）条。但实测发现乱码标题恰好出现在
        **第 10 位**（Tavily 对 `health.people.com.cn` 这种 gb2312 站点返回的标题本身就是乱码），
        于是它从未被抓取，也就无从替换 —— 只按排名决定抓谁，救不了这种情况。
        现在额外抓取"标题疑似乱码"的条目（受 {@code _EXTRA_FETCH_FOR_BAD_TITLE} 上限约束）。

        <p>总抓取量的硬上限（{@code _MAX_TOTAL_FETCH}）保证不会因为一次搜索命中太多坏标题
        而把时间预算耗光。</p>
        """
        fetch_top = settings.SEARCH_FETCH_TOP
        # fetch_top <= 0 表示"关闭抓取"这一整体开关（既有测试与离线环境依赖该语义），
        # 此时连"标题可疑"的额外抓取也要一起关掉。
        if fetch_top <= 0:
            return results
        # 排名内的照常抓；排名外但标题可疑的也要抓（否则永远修不了）
        indexes = set(range(min(fetch_top, len(results))))
        extra = 0
        for i, item in enumerate(results):
            if i in indexes or not item.url:
                continue
            if _looks_mojibake(item.title) and extra < _EXTRA_FETCH_FOR_BAD_TITLE:
                indexes.add(i)
                extra += 1
        if extra:
            logger.info("额外抓取 %d 条标题疑似乱码的结果（不受 SEARCH_FETCH_TOP 限制）", extra)

        fetched_count = 0
        for index in sorted(indexes):
            if fetched_count >= _MAX_TOTAL_FETCH:
                logger.info("抓取数量达到上限 %d，停止抓取", _MAX_TOTAL_FETCH)
                break
            item = results[index]
            if not item.url:
                continue
            # 单次抓取超时同样受配置上限 SEARCH_PAGE_TIMEOUT 约束，且不超过剩余预算
            timeout = min(settings.SEARCH_PAGE_TIMEOUT, self._remaining(deadline))
            if timeout <= 2:
                logger.info("预算不足，停止抓取网页正文")
                break
            fetched_count += 1
            try:
                page_title, text = self._fetch_page_text(item.url, timeout=timeout)
                if text:
                    item.content = text[: settings.SEARCH_PAGE_MAX_CHARS]
                    logger.info("已抓取网页正文：url=%s, length=%d", item.url, len(item.content))
                # 搜索接口给的标题是乱码时，用**我们抓到的真实标题**替换它。
                #
                # 为什么走"替换"而不是"还原乱码"：实测 Tavily 对 gb2312 站点
                # （如 health.people.com.cn）返回的 title/snippet 双重损坏、
                # 不可逆；而我们自己抓同一页面是干净的（编码已在 _decode_response 修好）。
                # 这条路径同时惠及 snippet：LLM 看到干净标题后就不会把乱码抄进证据。
                if page_title and _looks_mojibake(item.title):
                    logger.warning("搜索结果的标题疑似乱码，改用抓取到的页面标题：url=%s, 原=%r, 新=%r",
                                   item.url, item.title[:40], page_title[:60])
                    item.title = page_title
                if _looks_mojibake(item.snippet) and text:
                    # 摘要坏了就整段丢弃——留着乱码只会被模型抄进证据；
                    # 正文（item.content）已经可供模型阅读，摘要并非必需
                    logger.warning("搜索结果的摘要疑似乱码，已丢弃：url=%s", item.url)
                    item.snippet = ""
            except _EXPECTED_FETCH_ERRORS as exc:
                logger.warning("网页抓取失败，使用搜索摘要：url=%s, error=%s", item.url, exc)
        return results

    @staticmethod
    def _fetch_page_text(url: str, timeout: int = 10):
        """抓取网页，返回 (页面标题, 纯文本正文)

        安全约束（SSRF 防护）：
        - 只允许 http/https；
        - 拒绝内网/回环/链路本地/保留地址（含域名解析到这些地址的情况）；
        - **不自动跟随重定向**，每一跳都重新校验，避免"公网地址 302 到内网"绕过；
        - 主机解析失败按不可访问处理（fail-closed）。

        返回标题是为了在搜索接口给出乱码标题时能用**真实标题**替换它
        （见 `_enrich_results`）；标题从解码后的 HTML 里取，因此同样受益于
        `_decode_response` 的编码修复。
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

        html = _decode_response(resp)
        # 标题要在剥标签**之前**取，否则 <title> 内容会混进正文或被当成标签删掉
        page_title = _extract_html_title(html)
        # 去掉 script/style/noscript 等标签及其内容
        html = re.sub(r"(?is)<(script|style|noscript|header|footer|nav)\b[^>]*>.*?</(script|style|noscript|header|footer|nav)>", " ", html)
        # 去掉所有 HTML 标签
        text = re.sub(r"(?s)<[^>]+>", " ", html)
        # 常见 HTML 实体
        text = text.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        # 压缩空白
        text = re.sub(r"\s+", " ", text).strip()
        return page_title, text

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
                title = _repair_mojibake(str(item.get("title") or "").strip(), "模型证据.title")
                url = str(item.get("url") or "").strip()
                snippet = _repair_mojibake(str(item.get("snippet") or "").strip(), "模型证据.snippet")
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
                title=_repair_mojibake(r.title, "检索结果.title"),
                url=r.url,
                snippet=_repair_mojibake(r.snippet, "检索结果.snippet"),
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
                title=_repair_mojibake(r.title, "兜底证据.title"),
                url=r.url,
                snippet=_repair_mojibake(r.snippet, "兜底证据.snippet"),
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
