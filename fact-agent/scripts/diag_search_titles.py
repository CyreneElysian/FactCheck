"""诊断：用真实搜索词查看 people.com.cn 的标题/摘要是否已是乱码

背景：recordId=27 的 t_evidence 里，health.people.com.cn 那条标题是乱码，
而它的字节解出来确实是中文（D7A8 D2A3 D2B9 按 GBK = "专家："），
说明是"真实中文被误读"，且**我直接抓那个页面时是干净的**。
所以怀疑坏在搜索环节给的 title/snippet 上。本脚本把三条真实搜索词的结果摊开看。
"""

import sys

sys.path.insert(0, ".")
sys.stdout.reconfigure(encoding="utf-8")

from app.config import settings                      # noqa: E402
from app.services.search import get_search_tool      # noqa: E402

QUERIES = [
    "世界卫生组织 熬夜 2A类致癌物",
    "长期熬夜 一定会得癌症 吗",
    "IARC 昼夜节律紊乱 2A类 致癌物",
]


def judge(s: str) -> str:
    if not s:
        return "空"
    cjk = sum(1 for ch in s if "\u4e00" <= ch <= "\u9fff")
    hi = sum(1 for ch in s if "\u0080" <= ch <= "\u00ff")
    if cjk == 0 and hi > 3:
        return f"*** 乱码（汉字0/高位{hi}）"
    return f"正常（汉字{cjk}）"


tool = get_search_tool(settings.SEARCH_TOOL, settings.SEARCH_API_KEY)
print("搜索后端:", type(tool).__name__)
print()

flagged = 0
for q in QUERIES:
    print("=" * 76)
    print("查询:", q)
    print("=" * 76)
    try:
        results = tool.search(q, top_k=10, timeout=20)
    except Exception as exc:                          # noqa: BLE001
        print("  搜索失败:", type(exc).__name__, exc)
        continue
    for i, r in enumerate(results, 1):
        tv, sv = judge(r.title or ""), judge(r.snippet or "")
        is_bad = "乱码" in tv or "乱码" in sv
        if is_bad:
            flagged += 1
        mark = "  <<<<<< 乱码" if is_bad else ""
        print(f"  {i:2d}) [{r.source}] title={tv}{mark}")
        if is_bad:
            print(f"      title   : {r.title[:80]!r}")
            print(f"      snippet : {(r.snippet or '')[:80]!r}")
        if "people" in (r.url or ""):
            print(f"      ↑ 人民网 URL: {r.url}")
            print(f"        title   : {r.title[:90]}")
            print(f"        snippet : {(r.snippet or '')[:90]}")
    print()

print("=" * 76)
print(f"三条查询共发现乱码字段 {flagged} 个")
