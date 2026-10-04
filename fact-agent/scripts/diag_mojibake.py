"""一次性诊断：库里的乱码标题能否被还原（判断损坏是否可逆）"""

import sys

sys.stdout.reconfigure(encoding="utf-8")

# 库里存的实际 title（从 hex 复原）
corrupt = bytes.fromhex(
    "D7A8D2A3D2B93241C2B0C2A0CBAFC7B1C6B7DDB3C6B72D2D2D2D"
).decode("utf-8")

# 我抓取真实页面得到的正确标题
correct = "专家：熬夜属于2A类致癌物 睡眠是必需品不是奢侈品--健康·生活--人民网"

print("库里(乱码) :", repr(corrupt))
print("页面(正确) :", correct)
print()

print("=== 对照组：标准 latin-1 误读路径是可逆的 ===")
typical = correct.encode("utf-8").decode("latin-1")
print("  典型乱码 :", repr(typical[:40]))
print("  还原成功 :", repr(typical.encode("latin-1").decode("utf-8")[:40]))
print()

print("=== 库里这条：试各种还原路径 ===")
attempts = [
    ("latin-1 -> utf-8", lambda s: s.encode("latin-1").decode("utf-8")),
    ("latin-1 -> gbk", lambda s: s.encode("latin-1").decode("gbk")),
    ("latin-1 -> gb18030", lambda s: s.encode("latin-1").decode("gb18030")),
    ("utf-8 -> latin-1", lambda s: s.encode("utf-8").decode("latin-1")),
]
for name, fn in attempts:
    try:
        r = fn(corrupt)
        cjk = sum(1 for ch in r if "\u4e00" <= ch <= "\u9fff")
        print(f"  {name:<18} -> 汉字={cjk:2d}  {r}")
    except Exception as exc:  # noqa: BLE001
        print(f"  {name:<18} -> 失败 {type(exc).__name__}")

print()
print("=== 关键：库里字节里混着 C2B0 / C2A0 ===")
raw = bytes.fromhex("D7A8D2A3D2B93241C2B0C2A0CBAFC7B1C6B7DDB3C6B72D2D2D2D")
print("  原始字节:", " ".join(f"{b:02X}" for b in raw))
print("  注意 C2B0 与 C2A0 —— 这是 U+00B0(°) 与 U+00A0(空格) 的 **UTF-8 编码**")
print("  说明：GBK 中文先被 latin-1 读成乱码（产生 U+00B0 等），")
print("        该乱码串又被 **UTF-8 编码一次**才落库 → 双重损坏。")
print()
print("  其中 D7A8 D2A3 D2B9 是原始 GBK 字节；")
print("  C2B0 C2A0 却是 C2 B0 / C2 A0 —— 单字节 B0/A0 被 UTF-8 化的产物。")
print("  ⇒ 同一条串里混了两种损坏层级，所以不存在统一还原路径。")
print()
print("结论：这条数据的信息已不可恢复，修复必须放在**产生乱码的那一环之前**。")
