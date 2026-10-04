"""
自检：unverified_ab 的统计与结论逻辑（合成数据，不调用任何 API）

统计正确性与 API 无关，没必要为验证它去消耗额度。

## 重点验证「两个方向都要看」这条设计

本评测的核心风险是**只看目标指标**：一个把所有断言都判 UNVERIFIED 的模型，
能在「UNVERIFIED 命中数」上拿满分（15/15），但显然是不可接受的。
所以 `verdict_of` 必须在「目标改善但引入回归」时给出**有得有失**而不是"正向"。
"""

import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
sys.stdout.reconfigure(encoding="utf-8")

import unverified_ab as ab  # noqa: E402


def pair(pid, label, before_v, after_v=None):
    """构造一条配对记录（after_v 默认与 before_v 相同）"""
    after_v = before_v if after_v is None else after_v

    def arm(v):
        return {"ok": True, "verdict": v, "can_judge": True,
                "hit": v == label, "seconds": 20.0, "reasoning_chars": 400, "evidence_count": 10}

    return {"id": pid, "label": label, "category": "健康", "text": f"合成 {pid}",
            "before": arm(before_v), "after": arm(after_v)}


def main():
    fails = []

    # ---- 场景 1：理想的修好 —— 目标改善、无回归 ----
    rows = (
        [pair(i, "UNVERIFIED", "FALSE", "UNVERIFIED") for i in range(1, 7)]      # 6 条被修好
        + [pair(i, "FALSE", "FALSE") for i in range(10, 20)]                     # 10 条不变
        + [pair(i, "TRUE", "TRUE") for i in range(20, 30)]                       # 10 条不变
    )
    s = ab.summarize(rows)
    assert s["target_before"] == 0 and s["target_after"] == 6, s["target_after"]
    assert s["wrongly_unverified_before"] == 0 and s["wrongly_unverified_after"] == 0
    v, why = ab.verdict_of(s)
    assert "正向" in v, f"应判为正向，实际：{v}"
    print(f"  ✓ 场景1 理想修好被识别为正向：{v}")

    # ---- 场景 2：★ 关键 —— 目标改善但引入回归，必须报"有得有失" ----
    rows2 = (
        [pair(i, "UNVERIFIED", "FALSE", "UNVERIFIED") for i in range(1, 7)]      # 修好 6 条
        + [pair(i, "FALSE", "FALSE", "UNVERIFIED") for i in range(10, 14)]       # 但弄坏 4 条
        + [pair(i, "TRUE", "TRUE") for i in range(20, 28)]
    )
    s2 = ab.summarize(rows2)
    assert s2["target_after"] == 6, s2["target_after"]
    assert s2["wrongly_unverified_after"] == 4, s2["wrongly_unverified_after"]
    v2, _ = ab.verdict_of(s2)
    assert "有得有失" in v2, f"目标改善但引入回归时必须报有得有失，实际：{v2}"
    print(f"  ✓ 场景2 引入回归被正确识别：{v2}")

    # ---- 场景 3：全判 UNVERIFIED 的"作弊模型"必须被判为有得有失/-，而不是正向 ----
    rows3 = (
        [pair(i, "UNVERIFIED", "FALSE", "UNVERIFIED") for i in range(1, 16)]     # 15/15 满分
        + [pair(i, "FALSE", "FALSE", "UNVERIFIED") for i in range(20, 39)]       # 19 条全坏
        + [pair(i, "TRUE", "TRUE", "UNVERIFIED") for i in range(40, 51)]         # 11 条全坏
    )
    s3 = ab.summarize(rows3)
    assert s3["target_after"] == 15, "作弊模型在目标指标上是满分"
    assert s3["wrongly_unverified_after"] == 30, s3["wrongly_unverified_after"]
    v3, _ = ab.verdict_of(s3)
    assert "有得有失" in v3, f"作弊模型不得被判为正向，实际：{v3}"
    assert s3["after_acc"] < s3["before_acc"], "作弊模型总准确率必然下降"
    print(f"  ✓ 场景3 「全判 UNVERIFIED」的作弊模型未被误判为正向：{v3}")

    # ---- 场景 4：完全没变化 ----
    rows4 = [pair(i, "FALSE", "FALSE") for i in range(1, 12)]
    s4 = ab.summarize(rows4)
    v4, _ = ab.verdict_of(s4)
    assert "未观察到" in v4, v4
    print(f"  ✓ 场景4 无变化被正确识别：{v4}")

    # ---- 场景 5：目标反而变差 ----
    rows5 = (
        [pair(i, "UNVERIFIED", "UNVERIFIED", "FALSE") for i in range(1, 5)]
        + [pair(i, "FALSE", "FALSE") for i in range(10, 20)]
        + [pair(i, "TRUE", "TRUE") for i in range(20, 30)]
    )
    s5 = ab.summarize(rows5)
    v5, _ = ab.verdict_of(s5)
    assert "负向" in v5, v5
    print(f"  ✓ 场景5 负向被正确识别：{v5}")

    # ---- 场景 6：样本过少时拒绝下结论 ----
    s6 = ab.summarize([pair(1, "FALSE", "FALSE"), pair(2, "UNVERIFIED", "FALSE", "UNVERIFIED")])
    v6, _ = ab.verdict_of(s6)
    assert "样本过少" in v6, v6
    print(f"  ✓ 场景6 样本过少时拒绝下结论：{v6}")

    # ---- 场景 7：单臂失败要排除在配对统计外 ----
    rows7 = [pair(1, "FALSE", "FALSE"), pair(2, "FALSE", "FALSE")]
    rows7[1]["after"] = {"ok": False, "error": True, "hit": False, "seconds": 1.0}
    s7 = ab.summarize(rows7)
    assert s7["n_usable"] == 1 and s7["n_skipped"] == 1, s7
    print("  ✓ 场景7 单臂失败被排除：n_usable=1, n_skipped=1")

    # ---- 场景 8：混淆矩阵按标签分组 ----
    s8 = ab.summarize(rows)
    assert s8["label_confusion"]["UNVERIFIED"]["total"] == 6
    assert s8["label_confusion"]["UNVERIFIED"]["before"]["FALSE"] == 6, s8["label_confusion"]
    assert s8["label_confusion"]["UNVERIFIED"]["after"]["UNVERIFIED"] == 6
    print("  ✓ 场景8 逐标签混淆矩阵正确")

    # ---- 报告能生成且关键小节齐全 ----
    out = ab.write_report(s, rows, "2026-10-05 01:30:00")
    text = out.read_text(encoding="utf-8")
    required = ["## 0. 结论", "为什么必须同时看两个方向", "目标指标：UNVERIFIED",
                "回归面：被误判成 UNVERIFIED", "逐标签混淆矩阵", "逐条结果",
                "本次评测没有回答的问题"]
    missing = [r for r in required if r not in text]
    if missing:
        fails.append(f"报告缺少小节：{missing}")
    else:
        print(f"  ✓ 报告生成成功（{len(text)} 字符，小节齐全）")

    print()
    if fails:
        print("  ❌ 自检失败：")
        for f in fails:
            print("     -", f)
        return 1
    print("  ✅ 全部自检通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
