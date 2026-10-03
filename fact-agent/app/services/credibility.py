"""
来源可信度评分（S13）

规则从 **`fact-agent/config/credibility_rules.json`** 读取（可用环境变量
`CREDIBILITY_RULES_FILE` 指向别的文件），因此**改词表不需要改代码、也不需要改测试**：
增删域名、调整分值、加一档"仅可参考"的站点，只动 JSON 即可。

设计要点：
- **确定性**：同一域名永远得到同一分值，可审计、可复核。因此证据可信度以本模块为准，
  不采信模型自报的数字（模型可以影响结论，但不能影响来源评级）。
- **可降级**：规则文件缺失或写坏时，回退到内置的一份兜底规则并打 ERROR 日志，
  Agent 不会因为改坏配置而起不来（配置是给人改的，必须假设会改错）。
- **未收录 ≠ 不可信**：未收录域名落到 `unknown_score`，语义是"未收录"，不是"判定为假"。

匹配顺序：按 `tiers` 的先后顺序，命中即返回该档分值。
`exact` 为完整域名精确匹配，`suffixes` 为后缀匹配（`wjw.wuhan.gov.cn` 命中 `gov.cn`）。
"""
import json
import logging
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple
from urllib.parse import urlparse

logger = logging.getLogger(__name__)

# 未收录域名的兜底可信度（同时也是"域名未收录"的判定阈值）
DEFAULT_UNKNOWN_SCORE = 0.3

# 规则文件路径的环境变量名
RULES_FILE_ENV = "CREDIBILITY_RULES_FILE"

# 默认规则文件：fact-agent/config/credibility_rules.json
DEFAULT_RULES_FILE = Path(__file__).resolve().parents[2] / "config" / "credibility_rules.json"

# 内置兜底规则：与默认 JSON 的内容保持一致，仅在文件缺失/损坏时使用
_BUILTIN_TIERS: Tuple[Tuple[str, float, Tuple[str, ...]], ...] = (
    ("官方/权威", 0.95, (
        "gov.cn", "people.com.cn", "xinhuanet.com", "news.cn", "cctv.com",
        "chinanews.com.cn", "china.com.cn", "gmw.cn", "who.int", "edu.cn", "org.cn",
        "kepuchina.cn", "piyao.org.cn", "chinacdc.cn",
    )),
    ("主流媒体/平台", 0.5, (
        "baidu.com", "wikipedia.org", "zhihu.com", "weibo.com", "sohu.com",
        "163.com", "sina.com.cn", "qq.com", "ifeng.com", "thepaper.cn", "guancha.cn",
    )),
)


@dataclass(frozen=True)
class CredibilityTier:
    """一档可信度规则"""

    name: str
    score: float
    exact: Tuple[str, ...]
    suffixes: Tuple[str, ...]

    def matches(self, domain: str) -> bool:
        if domain in self.exact:
            return True
        return any(domain == s or domain.endswith("." + s) for s in self.suffixes)


@dataclass(frozen=True)
class CredibilityRules:
    """一份已加载的可信度规则"""

    unknown_score: float
    tiers: Tuple[CredibilityTier, ...]
    version: int
    source: str

    def score(self, url: str) -> float:
        domain = _host_of(url)
        if not domain:
            return self.unknown_score
        for tier in self.tiers:
            if tier.matches(domain):
                return tier.score
        return self.unknown_score

    def is_known(self, url: str) -> bool:
        return self.score(url) > self.unknown_score

    def describe(self) -> str:
        """供启动自检打印：规则来自哪里、有几档、共多少条"""
        total = sum(len(t.exact) + len(t.suffixes) for t in self.tiers)
        detail = "、".join(f"{t.name}={t.score}" for t in self.tiers)
        return f"{self.source}（v{self.version}，{len(self.tiers)} 档 / {total} 条，{detail}）"


def _host_of(url: str) -> str:
    """取 URL 的域名（小写、去端口）"""
    if not url:
        return ""
    try:
        # netloc 形如 user:pass@host:port，这里取 hostname 更稳（会自动去端口与大小写归一）
        return (urlparse(url).hostname or "").lower()
    except Exception:
        return ""


def _resolve_path(path: Optional[Path]) -> Path:
    if path is not None:
        return Path(path)
    override = os.getenv(RULES_FILE_ENV, "").strip()
    if override:
        candidate = Path(override)
        if not candidate.is_absolute():
            candidate = (Path.cwd() / candidate).resolve()
        return candidate
    return DEFAULT_RULES_FILE


def _parse(raw: Dict[str, Any], source: str) -> CredibilityRules:
    """解析规则文件；非法条目跳过并告警，不因为一条坏规则整份失效"""
    unknown = raw.get("unknown_score", DEFAULT_UNKNOWN_SCORE)
    try:
        unknown = float(unknown)
        if not 0.0 <= unknown <= 1.0:
            raise ValueError("超出 0-1")
    except (TypeError, ValueError) as exc:
        logger.warning("unknown_score 非法（%s），改用默认值 %s", exc, DEFAULT_UNKNOWN_SCORE)
        unknown = DEFAULT_UNKNOWN_SCORE

    tiers: List[CredibilityTier] = []
    for index, item in enumerate(raw.get("tiers") or []):
        if not isinstance(item, dict):
            logger.warning("跳过非法规则档（非对象）：第 %d 档", index + 1)
            continue
        try:
            score = float(item["score"])
            if not 0.0 <= score <= 1.0:
                raise ValueError("score 超出 0-1")
        except (KeyError, TypeError, ValueError) as exc:
            logger.warning("跳过非法规则档（score 无效：%s）：%s", exc, item.get("name"))
            continue
        exact = tuple(str(x).strip().lower() for x in (item.get("exact") or []) if str(x).strip())
        suffixes = tuple(str(x).strip().lower() for x in (item.get("suffixes") or []) if str(x).strip())
        if not exact and not suffixes:
            logger.warning("跳过空规则档（既无 exact 也无 suffixes）：%s", item.get("name"))
            continue
        tiers.append(CredibilityTier(
            name=str(item.get("name") or f"第{index + 1}档"),
            score=score,
            exact=exact,
            suffixes=suffixes,
        ))

    if not tiers:
        raise ValueError("规则文件中没有任何有效档位")

    version = raw.get("version", 1)
    try:
        version = int(version)
    except (TypeError, ValueError):
        version = 1
    return CredibilityRules(unknown_score=unknown, tiers=tuple(tiers), version=version, source=source)


def _builtin_rules() -> CredibilityRules:
    tiers = tuple(
        CredibilityTier(name=name, score=score, exact=(), suffixes=suffixes)
        for name, score, suffixes in _BUILTIN_TIERS
    )
    return CredibilityRules(
        unknown_score=DEFAULT_UNKNOWN_SCORE,
        tiers=tiers,
        version=0,
        source="内置兜底规则（未读取到规则文件）",
    )


def load_rules(path: Optional[Path] = None) -> CredibilityRules:
    """
    加载可信度规则

    :param path: 显式指定规则文件（测试用）；为空时按 `CREDIBILITY_RULES_FILE` → 默认路径解析
    :return: 规则对象；文件缺失或损坏时返回内置兜底规则，**不抛异常**
    """
    target = _resolve_path(path)
    try:
        raw = json.loads(target.read_text(encoding="utf-8"))
        if not isinstance(raw, dict):
            raise ValueError("规则文件根节点必须是对象")
        rules = _parse(raw, str(target))
        logger.info("可信度规则已加载：%s", rules.describe())
        return rules
    except FileNotFoundError:
        logger.error("可信度规则文件不存在，改用内置兜底规则：%s（可用 %s 指定其它路径）",
                     target, RULES_FILE_ENV)
    except Exception as exc:
        logger.error("可信度规则文件解析失败，改用内置兜底规则：%s（%s）", target, exc)
    return _builtin_rules()


# 模块级单例：进程内规则不变（改文件后重启生效，避免每次请求都读盘）
_rules: CredibilityRules = load_rules()


def score_source(url: str) -> float:
    """
    根据 URL 域名返回可信度 0-1

    该结果是**确定性且可审计**的：同一域名永远得到同一分值。
    因此证据可信度以本函数为准，不采信模型自报的数字
    （模型可以影响结论，但不能影响来源评级——自报分值无法复核）。
    """
    return _rules.score(url)


def is_known_domain(url: str) -> bool:
    """域名是否已被可信度规则表收录"""
    return _rules.is_known(url)


def current_rules() -> CredibilityRules:
    """当前生效的规则（供启动自检与测试查看来源）"""
    return _rules
