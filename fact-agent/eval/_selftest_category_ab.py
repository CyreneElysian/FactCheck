"""自检：用合成数据验证 category_ab 的统计与报告逻辑（不调用任何 API）

这是脚本的"单元测试"，用来确认：
  * summarize() 的四格表、准确率、McNemar p 值算得对；
  * verdict_of() 的分级判断符合预期（含"负向信号"要能报出来）；
  * write_report() 能生成完整 Markdown（表头与关键小节都在）。

用合成数据而不是真实评测的原因：统计逻辑与 API 无关，
没必要为了验证它去消耗额度。
"""

import io
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
sys.stdout.reconfigure(encoding="utf-8")

import category_ab as ab  # noqa: E402


def make_pair(pid, label, base_hit, guided_hit, base_verdict, guided_verdict=None):
    """构造一条配对记录"""
    return {
        "id": pid,
        "label": label,
        "text": f"合成样本 {pid}",
        "baseline": {
            "ok": True, "hit": base_hit, "verdict": base_verdict, "can_judge": True,
            "criteria": {"风险等级(2A/2B/1类)": base_hit},
            "seconds": 10.0, "evidence_count": 5, "reasoning_chars": 200,
        },
        "guided": {
            "ok": True, "hit": guided_hit,
            "verdict": guided_verdict or base_verdict, "can_judge": True,
            "criteria": {"风险等级(2A/2B/1类)": guided_hit},
            "seconds": 12.0, "evidence_count": 6, "reasoning_chars": 400,
        },
    }


def main():
    failures = []

    # ---- 场景 1：Guided 胜出 4 条、Baseline 胜出 0 条 ----
    pairs = [make_pair(i, "FALSE", False, True, "UNVERIFIED", "FALSE") for i in range(1, 5)]
    s = ab.summarize(pairs)
    assert s["n_usable"] == 4, s["n_usable"]
    assert s["baseline_hits"] == 0 and s["guided_hits"] == 4, s
    assert s["mcnemar"]["only_guided"] == 4 and s["mcnemar"]["only_baseline"] == 0, s["mcnemar"]
    # b+c=4 且 b=0 → 精确 p = 2 * (1/16) = 0.125
    assert abs(s["mcnemar"]["p_value"] - 0.125) < 1e-9, s["mcnemar"]["p_value"]
    print("  ✓ 场景1 统计正确：0/4 → 4/4，p=0.125")

    # ---- 场景 2：差异不显著（3 : 3）。用 6 条以越过"样本过少"守卫（n<5 时不下结论）----
    pairs2 = ([make_pair(i, "FALSE", False, True, "UNVERIFIED", "FALSE") for i in range(1, 4)]
              + [make_pair(i, "FALSE", True, False, "FALSE", "UNVERIFIED") for i in range(4, 7)])
    s2 = ab.summarize(pairs2)
    assert s2["n_usable"] == 6, s2["n_usable"]
    assert s2["mcnemar"]["only_guided"] == 3 and s2["mcnemar"]["only_baseline"] == 3, s2["mcnemar"]
    # 3:3 → p = 2 * (C(6,0)+C(6,1)+C(6,2)+C(6,3))/2^6 = 2 * (1+6+15+20)/64 = 1.3125 → 截到 1.0
    assert abs(s2["mcnemar"]["p_value"] - 1.0) < 1e-9, s2["mcnemar"]["p_value"]
    v2, _ = ab.verdict_of(s2)
    assert "不显著" in v2 or "相同" in v2, v2
    print(f"  ✓ 场景2 正确识别为无差异：{v2}")

    # ---- 场景 3：负向信号必须能报出来（防止脚本只报喜）----
    pairs3 = [make_pair(i, "FALSE", True, False, "FALSE", "UNVERIFIED") for i in range(1, 11)]
    s3 = ab.summarize(pairs3)
    assert s3["delta"] < 0, s3["delta"]
    v3, why3 = ab.verdict_of(s3)
    assert "负向" in v3, f"负向信号未被识别：{v3}"
    print(f"  ✓ 场景3 负向信号被正确报出：{v3}（{why3}）")

    # ---- 场景 4：两臂完全一致 ----
    pairs4 = [make_pair(i, "FALSE", True, True, "FALSE") for i in range(1, 6)]
    s4 = ab.summarize(pairs4)
    assert s4["delta"] == 0 and s4["verdict_disagreement"] == 0, s4
    v4, _ = ab.verdict_of(s4)
    assert "完全一致" in v4, v4
    print(f"  ✓ 场景4 完全一致被正确识别：{v4}")

    # ---- 场景 5：单臂失败要排除在配对统计外 ----
    pairs5 = [make_pair(1, "FALSE", True, True, "FALSE"),
              make_pair(2, "FALSE", True, True, "FALSE")]
    pairs5[1]["guided"] = {"ok": False, "error": True, "hit": False, "criteria": {}, "seconds": 1.0}
    s5 = ab.summarize(pairs5)
    assert s5["n_usable"] == 1 and s5["n_skipped"] == 1, s5
    print("  ✓ 场景5 单臂失败被排除：n_usable=1, n_skipped=1")

    # ---- 场景 6：样本过少时不得给出结论 ----
    s6 = ab.summarize([make_pair(1, "FALSE", False, True, "UNVERIFIED", "FALSE")])
    v6, _ = ab.verdict_of(s6)
    assert "样本过少" in v6, v6
    print(f"  ✓ 场景6 样本过少时拒绝下结论：{v6}")

    # ---- 报告能生成且包含关键小节 ----
    out = ab.write_report(s, pairs, dataset_size=33, generated_at="2026-10-05 00:30:00")
    text = io.open(out, encoding="utf-8").read()
    required = ["## 0. 一句话结论", "配对四格表", "McNemar", "副指标", "逐条结果",
                "这次评测**没有**回答的问题", "复现方式"]
    missing = [r for r in required if r not in text]
    if missing:
        failures.append(f"报告缺少小节：{missing}")
    else:
        print(f"  ✓ 报告生成成功（{len(text)} 字符，小节齐全）")

    print()
    if failures:
        print("  ❌ 自检失败：")
        for f in failures:
            print("     -", f)
        return 1
    print("  ✅ 全部自检通过（统计逻辑与报告生成均正常）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
