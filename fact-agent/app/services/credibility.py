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
    "news.cn",
    "cctv.com",
    "chinanews.com.cn",
    "china.com.cn",
    "gmw.cn",
    "who.int",
    "edu.cn",
    "org.cn",
    # 国家级辟谣与科普平台
    "kepuchina.cn",
    "piyao.org.cn",
    "chinacdc.cn",
)

MEDIUM_CREDIBILITY_DOMAINS = (
    "baidu.com",
    "wikipedia.org",
    "zhihu.com",
    "weibo.com",
    "sohu.com",
    "163.com",
    "sina.com.cn",
    "qq.com",
    "ifeng.com",
    "thepaper.cn",
    "guancha.cn",
)

# 说明：本表是"起步版"，只覆盖最主流的站点。
# 未收录域名（含部分权威机构自建域名，如某些省级疾控 .com 站点）会落到 UNKNOWN_SCORE。
# 后续可扩充域名库，或改为由管理员在后台维护（见项目改进清单）。

# 未收录域名的兜底可信度（同时也是"域名未收录"的判定阈值）
UNKNOWN_SCORE = 0.3


def score_source(url: str) -> float:
    """
    根据 URL 域名返回可信度 0-1

    该结果是**确定性且可审计**的：同一域名永远得到同一分值。
    因此证据可信度以本函数为准，不采信模型自报的数字
    （模型可以影响结论，但不能影响来源评级——自报分值无法复核）。
    """
    try:
        domain = urlparse(url).netloc.lower()
    except Exception:
        return UNKNOWN_SCORE
    if not domain:
        return UNKNOWN_SCORE
    if any(domain == d or domain.endswith("." + d) for d in HIGH_CREDIBILITY_DOMAINS):
        return 0.95
    if any(domain == d or domain.endswith("." + d) for d in MEDIUM_CREDIBILITY_DOMAINS):
        return 0.5
    return UNKNOWN_SCORE


def is_known_domain(url: str) -> bool:
    """域名是否已被可信度规则表收录"""
    return score_source(url) > UNKNOWN_SCORE
