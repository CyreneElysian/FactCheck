"""
诊断脚本：抓取真实的人民网页面，逐步打印编码判定过程。

用途：排查"修复后人民网仍是乱码"——要把"HTTP 头声明的编码"、
"HTML meta 声明的编码"、"charset_normalizer 的推断"、"最终解出的文本"
全部摊开来看，而不是猜。

用法（需要联网）：
    python scripts/diagnose_encoding.py [url]
"""

import io
import re
import sys
from urllib.parse import urlparse

import requests

sys.path.insert(0, ".")
sys.stdout.reconfigure(encoding="utf-8")

URL = sys.argv[1] if len(sys.argv) > 1 else \
    "http://health.people.com.cn/n1/2023/0319/c14739-32646993.html"

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                  "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"
}

print("=" * 78)
print("URL:", URL)
print("=" * 78)

try:
    resp = requests.get(URL, headers=HEADERS, timeout=15, allow_redirects=True)
except Exception as exc:                                   # noqa: BLE001
    print("请求失败：", type(exc).__name__, exc)
    raise SystemExit(1)

print("状态码        :", resp.status_code)
print("最终 URL      :", resp.url)
print("Content-Type  :", resp.headers.get("Content-Type"))
print("响应体长度    :", len(resp.content), "字节")
print("resp.encoding :", repr(resp.encoding), "  <- requests 依据 HTTP 头得出的编码")
print("apparent_enc  :", repr(resp.apparent_encoding), "  <- 统计推断（本项目 venv 无 chardet）")

# --- HTML 内部声明的编码 -------------------------------------------------
head = resp.content[:4096]
meta = re.search(rb'charset\s*=\s*["\']?\s*([A-Za-z0-9_\-]+)', head, re.I)
print("HTML meta     :", meta.group(1).decode("ascii", "replace") if meta else "（前 4KB 内未声明）")

# --- 各候选解码结果（各取前 40 字符对比）--------------------------------
print()
print("-" * 78)
print("各候选编码解出的开头（看哪个是正常中文）")
print("-" * 78)
for enc in ["utf-8", "gbk", "gb18030", "big5", "latin-1"]:
    try:
        text = resp.content.decode(enc, errors="replace")
        # 去掉标签后取一段正文，避免看到的全是 HTML
        plain = re.sub(r"(?s)<[^>]+>", " ", text)
        plain = re.sub(r"\s+", " ", plain).strip()
        # 找标题标签里的内容更直观
        m = re.search(r"(?is)<title[^>]*>(.*?)</title>", text)
        title = re.sub(r"\s+", " ", m.group(1)).strip() if m else "(无 title)"
        cjk = sum(1 for ch in plain[:2000] if "\u4e00" <= ch <= "\u9fff")
        print(f"  {enc:<8} title={title[:38]:<40} 正文前2000字中汉字数={cjk}")
    except Exception as exc:                              # noqa: BLE001
        print(f"  {enc:<8} 解码失败：{type(exc).__name__}")

# --- 走项目里的真实实现 -------------------------------------------------
print()
print("-" * 78)
print("项目实现 _decode_response 的结果")
print("-" * 78)
from app.services.detect_service import _decode_response  # noqa: E402

text = _decode_response(resp)
plain = re.sub(r"(?s)<[^>]+>", " ", text)
plain = re.sub(r"\s+", " ", plain).strip()
m = re.search(r"(?is)<title[^>]*>(.*?)</title>", text)
print("  title   :", (re.sub(r"\s+", " ", m.group(1)).strip() if m else "(无)")[:70])
print("  正文开头:", plain[:90])
cjk = sum(1 for ch in plain[:2000] if "\u4e00" <= ch <= "\u9fff")
repl = plain[:2000].count("\ufffd")
print(f"  正文前2000字中汉字数={cjk}，替换字符(U+FFFD)={repl}")
print()
print("  判定：", "✅ 正常中文" if cjk > 50 and repl < 5 else "❌ 仍是乱码/未正确解码")

# --- charset_normalizer 单独看 -------------------------------------------------
print()
print("-" * 78)
print("charset_normalizer 的推断")
print("-" * 78)
try:
    from charset_normalizer import from_bytes

    best = from_bytes(resp.content).best()
    if best:
        print("  encoding :", best.encoding)
        print("  chaos    :", getattr(best, "chaos", "?"), " coherence:", getattr(best, "coherence", "?"))
        print("  语言     :", getattr(best, "language", "?"))
        t = str(best)
        p = re.sub(r"\s+", " ", re.sub(r"(?s)<[^>]+>", " ", t)).strip()
        print("  解出开头 :", p[:80])
    else:
        print("  推断失败（best() 返回 None）")
except Exception as exc:                                  # noqa: BLE001
    print("  charset_normalizer 不可用：", type(exc).__name__, exc)
