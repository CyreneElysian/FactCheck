"""
来源可信度评分（S13）

内置规则：官方/权威域名高分，自媒体/未知域名低分。
后续可替换为更完整的域名库或由管理员人工修正。
"""
from urllib.parse import urlparse

HIGH_CREDIBILITY_DOMAINS = (
    "gov.cn",
    "people.com.cn",
    "xinhuanet.com",
    "cctv.com",
    "chinanews.com.cn",
    "who.int",
    "edu.cn",
    "org.cn",
)

MEDIUM_CREDIBILITY_DOMAINS = (
    "baidu.com",
    "wikipedia.org",
    "zhihu.com",
    "weibo.com",
    "sohu.com",
    "163.com",
    "sina.com.cn",
)


def score_source(url: str) -> float:
    """
    根据 URL 域名返回可信度 0-1
    """
    try:
        domain = urlparse(url).netloc.lower()
    except Exception:
        return 0.0
    if not domain:
        return 0.0
    if any(domain == d or domain.endswith("." + d) for d in HIGH_CREDIBILITY_DOMAINS):
        return 0.95
    if any(domain == d or domain.endswith("." + d) for d in MEDIUM_CREDIBILITY_DOMAINS):
        return 0.5
    return 0.3
