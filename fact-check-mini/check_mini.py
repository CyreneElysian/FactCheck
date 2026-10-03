"""
小程序静态自检（不依赖微信开发者工具）

检查项（都是"改了不带工具也能发现"的错误类型）：
1. 所有 .js 过 `node --check`（语法错误）
2. 所有 .json 能被解析（app.json / 页面 json / project.config.json）
3. app.json 里每个页面都有 .js/.wxml/.json 三个文件
4. 每个页面 .js 里 `wx.navigateTo` 的目标页都在 app.json 注册过（防拼错路径）
5. WXML 里 `bindtap`/`catchtap`/`bindinput` 等绑定的方法在对应 .js 里存在
   （微信只会在运行时报 warn，很容易漏）
"""
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
failures = []
checks = 0


def fail(msg):
    failures.append(msg)
    print(f"  [FAIL] {msg}")


def ok(msg):
    print(f"  [ok] {msg}")


# ---------------------------------------------------------------- 1. JS 语法
js_files = sorted(p for p in ROOT.rglob("*.js") if "node_modules" not in str(p))
for path in js_files:
    checks += 1
    proc = subprocess.run(["node", "--check", str(path)], capture_output=True, text=True, shell=False)
    if proc.returncode != 0:
        fail(f"JS 语法错误 {path.relative_to(ROOT)}: {proc.stderr.strip().splitlines()[:2]}")
print(f"1) JS 语法：检查 {len(js_files)} 个文件")


# ---------------------------------------------------------------- 2. JSON
json_files = sorted(p for p in ROOT.rglob("*.json") if "node_modules" not in str(p))
for path in json_files:
    checks += 1
    try:
        json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        fail(f"JSON 非法 {path.relative_to(ROOT)}: {exc}")
print(f"2) JSON：检查 {len(json_files)} 个文件")


# ---------------------------------------------------------------- 3. 页面文件齐套
app_json = json.loads((ROOT / "app.json").read_text(encoding="utf-8"))
pages = app_json.get("pages", [])
for page in pages:
    checks += 1
    missing = [ext for ext in (".js", ".wxml", ".json") if not (ROOT / f"{page}{ext}").exists()]
    if missing:
        fail(f"页面 {page} 缺少文件：{missing}")
print(f"3) 页面齐套：检查 {len(pages)} 个页面")


# ------------------------------------------------- 4. navigateTo 目标已注册
registered = set(pages)
for path in js_files:
    text = path.read_text(encoding="utf-8")
    for target in re.findall(r"url:\s*[`'\"](/pages/[^`'\"?\s]+)", text):
        checks += 1
        page = target.lstrip("/")
        if page not in registered:
            fail(f"{path.relative_to(ROOT)} 跳转到未注册页面：{target}")
print("4) 页面跳转目标：检查完成")


# ------------------------------------------------- 5. WXML 事件绑定的方法存在
BIND_RE = re.compile(r'\b(?:bind|catch)(?::)?(tap|input|change|confirm|longpress|blur|focus|submit)\s*=\s*"([A-Za-z_$][\w$]*)"')
for wxml in sorted(ROOT.rglob("*.wxml")):
    js = wxml.with_suffix(".js")
    if not js.exists():
        continue
    js_text = js.read_text(encoding="utf-8")
    wxml_text = wxml.read_text(encoding="utf-8")
    for _event, handler in BIND_RE.findall(wxml_text):
        checks += 1
        # 方法可以写成 `handler(` 或 `handler:` 或 `handler =`
        if not re.search(rf"\b{re.escape(handler)}\s*[(:]", js_text):
            fail(f"{wxml.relative_to(ROOT)} 绑定了不存在的方法 {handler}()")
print("5) WXML 事件绑定：检查完成")


print()
if failures:
    print(f"结果：{len(failures)} 项失败（共 {checks} 项检查）")
    sys.exit(1)
print(f"结果：全部通过（共 {checks} 项检查）")
