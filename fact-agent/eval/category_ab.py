"""
分类提示词 A/B 对照评测（回答"分类提示词到底有没有用"）
================================================================================

## 这个脚本解决什么问题

`detect_prompt.CATEGORY_GUIDANCE` 目前只对「健康」启用。启用后能观察到
"系统提示词变长了""日志里出现已追加分类专属核查注意事项"，但**这些都不能证明
判断质量变好了** —— 单看某一条断言判对了，无法区分是提示词的功劳还是模型本来就会。

所以需要一个**配对对照（paired A/B）**：
    对同一条断言跑两次，唯一差别是 `category` 传不传：
        Arm B（Baseline，对照）：不传 category → 通用提示词
        Arm G（Guided，实验）  ：传 category=健康 → 通用提示词 + 健康类核查重点
    两次用**同一个模型、同一份搜索源**，逐条比较。

配对设计的好处：断言本身的难易程度被抵消掉，比较的是"同一批题在两个条件下的表现"。
（若用两组不同题目对比，题目难度差异会混进结果里。）

## 两个指标

**主指标：准确率（verdict 与人工标签一致）**
    只看 can_judge=true 且 verdict ∈ {TRUE, FALSE} 的样本；拒答（can_judge=false）
    与判 UNVERIFIED 都按"未命中"计入分母 —— 分母固定，条件之间才可比。

**副指标：推理链是否命中关键判据**
    按关键词命中率统计（见 CRITERION_KEYWORDS）。这一项**只能作为线索**：
    它衡量的是"模型有没有说出我们想让它说的东西"，不等于判断更正确。

## 统计口径（重要，避免过度解读）

样本量小（健康类共 33 条，默认还会抽子集），因此：
  * 报告**同时给出**原始混淆矩阵与差值，让人自己看；
  * 配对二分类用 **McNemar 精确检验**（标准库实现，不引入 scipy）；
  * 差值的 95% 置信区间用 Wald 区间，并在 n 小的时候明确标注"仅供参考"。
**不要把 p=0.06 当成"有效"、也不要因为 p=0.4 就说"没用"** ——
这个样本量只能发现**较大**的差异。

## 用法

    # 零成本预检：检查样本、打印将执行的调用次数估算（不联网）
    python eval/category_ab.py --dry-run

    # 小规模试跑（建议先 6 条，确认流程与花费）
    python eval/category_ab.py --subset 6

    # 跑全部健康类样本
    python eval/category_ab.py --all

    # 只重新合成报告（用已保存的原始结果，不再调用 API）
    python eval/category_ab.py --report

结果：
    eval/results/category_ab.json      原始逐条结果（可复核）
    docs/分类提示词对照报告.md           报告

## 成本

每条样本每臂会真实调用：LLM 生成搜索词 + 1~3 次搜索 + 抓取若干网页 + 1 次 LLM 判定。
两臂合计约 **2×（n 条）** 次完整检测链路。默认子集 8 条 → 约 16 次检测。
断点续跑：每跑完一条就落盘，中断后重跑会跳过已完成的 (id, arm)。
"""
import argparse
import json
import math
import pathlib
import statistics
import sys
import time
from datetime import datetime

EVAL_DIR = pathlib.Path(__file__).resolve().parent
FACT_AGENT = EVAL_DIR.parent
REPO_ROOT = FACT_AGENT.parent
sys.path.insert(0, str(FACT_AGENT))

# Windows 控制台默认是 GBK，打印中文/emoji（如 ⚠️）会抛
# UnicodeEncodeError: 'gbk' codec can't encode character。本脚本要能**直接单独运行**，
# 不能依赖调用方先设好 PYTHONIOENCODING，所以在这里自己兜住。
try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except (AttributeError, OSError):                     # 非标准流（被重定向）时忽略
    pass

from app.config import settings                      # noqa: E402
from app.services.detect_service import DetectService  # noqa: E402
from app.services.llm import OpenAICompatibleLLM     # noqa: E402
from app.services.search import get_search_tool      # noqa: E402

DATASET = EVAL_DIR / "dataset.jsonl"
RESULTS_DIR = EVAL_DIR / "results"
DOCS_DIR = REPO_ROOT / "docs"
RAW_NAME = "category_ab"

# 只评测「健康」类：其余分类目前没有专属指导，两臂完全相同（跑了纯属浪费额度）
TARGET_CATEGORY = "健康"

# 推理链"关键判据"关键词。
# 只取与"健康类通用推理模式"有关的三条（与 CATEGORY_GUIDANCE 的第 1~3 条对应）：
#   * 2A/2B/1 类 —— 风险等级用词
#   * 因果 / 相关 —— 偷换因果
#   * 相对风险 / 绝对风险 —— 风险幅度误读
# 第 4 条（被列对象是否被替换）与第 5 条（来源优先级）与具体断言强相关，
# 不适合做全量统计，故不纳入。
CRITERION_KEYWORDS = {
    "风险等级(2A/2B/1类)": ["2a", "2b", "1类", "一类", "iarc", "致癌物分级"],
    "因果vs相关": ["因果", "相关", "关联"],
    "相对vs绝对风险": ["相对风险", "绝对风险", "风险升高", "百分比", "统计学意义"],
}


def load_dataset():
    rows = []
    with DATASET.open(encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def build_service():
    """构造真实的检测服务（联网）"""
    tool = get_search_tool(settings.SEARCH_TOOL, settings.SEARCH_API_KEY)
    llm = OpenAICompatibleLLM(
        api_key=settings.LLM_API_KEY,
        base_url=settings.LLM_BASE_URL,
        model=settings.LLM_MODEL,
    )
    return DetectService(search_tool=tool, llm=llm)


def hit(verdict, can_judge, label) -> bool:
    """
    是否命中人工标签。

    口径（固定分母，两臂才可比）：
      * can_judge=false（拒答）→ 未命中
      * verdict=UNVERIFIED      → 未命中
      * 否则 verdict == label   → 命中
    """
    if not can_judge or verdict in (None, "UNVERIFIED"):
        return False
    return verdict == label


def chain_hits(reasoning: str) -> dict:
    """推理链命中了哪些关键判据（关键词口径，仅供线索）"""
    text = (reasoning or "").lower()
    return {name: any(kw in text for kw in kws) for name, kws in CRITERION_KEYWORDS.items()}


def run_one(service, text, category, label):
    """跑一条样本，返回可落盘的记录"""
    started = time.time()
    try:
        resp = service.detect(text, category)
        elapsed = round(time.time() - started, 1)
        verdict = getattr(resp, "verdict", None)
        can_judge = bool(getattr(resp, "can_judge", False))
        reasoning = getattr(resp, "reasoning_chain", None) or ""
        evidence = getattr(resp, "evidence", None) or []
        return {
            "ok": True,
            "verdict": verdict,
            "can_judge": can_judge,
            "confidence": getattr(resp, "confidence", None),
            "error": bool(getattr(resp, "error", False)),
            "human_reason": getattr(resp, "human_reason", None),
            "reasoning_chars": len(reasoning),
            "evidence_count": len(evidence),
            "hit": hit(verdict, can_judge, label),
            "criteria": chain_hits(reasoning),
            "seconds": elapsed,
        }
    except Exception as exc:                                   # noqa: BLE001
        # 单条失败不能中断整轮评测；记为 ok=false，统计时单独列示
        return {
            "ok": False,
            "error": True,
            "failure": f"{type(exc).__name__}: {exc}",
            "hit": False,
            "criteria": {},
            "seconds": round(time.time() - started, 1),
        }


# ----------------------------------------------------------------------
# 统计
# ----------------------------------------------------------------------


def wilson_interval(successes: int, total: int, z: float = 1.96):
    """Wilson 区间：小样本比 Wald 区间稳（n=15 时 Wald 会给出越界或过窄的区间）"""
    if total == 0:
        return (0.0, 0.0)
    p = successes / total
    denom = 1 + z * z / total
    center = (p + z * z / (2 * total)) / denom
    half = z * math.sqrt(p * (1 - p) / total + z * z / (4 * total * total)) / denom
    return (max(0.0, center - half), min(1.0, center + half))


def mcnemar_exact(b: int, c: int):
    """
    McNemar 精确检验（配对二分类）。

    b = 对照命中、实验未命中 的条数
    c = 对照未命中、实验命中 的条数
    原假设"两臂表现相同"下，b 服从 Binomial(b+c, 0.5)。
    返回双侧 p 值。用标准库实现，避免引入 scipy。
    """
    n = b + c
    if n == 0:
        return 1.0
    # 精确二项检验：p = 2 * P(X <= min(b,c))
    k = min(b, c)
    tail = sum(math.comb(n, i) for i in range(0, k + 1)) / (2 ** n)
    return min(1.0, 2 * tail)


def summarize(paired):
    """把逐条结果汇总成两臂对比"""
    # 只统计两臂都成功完成的样本，保证配对有效
    usable = [p for p in paired if p["baseline"].get("ok") and p["guided"].get("ok")]
    skipped = len(paired) - len(usable)

    n = len(usable)
    b_hit = sum(1 for p in usable if p["baseline"].get("hit"))
    g_hit = sum(1 for p in usable if p["guided"].get("hit"))

    # 配对四格表
    both = sum(1 for p in usable if p["baseline"].get("hit") and p["guided"].get("hit"))
    only_b = sum(1 for p in usable if p["baseline"].get("hit") and not p["guided"].get("hit"))
    only_g = sum(1 for p in usable if p["guided"].get("hit") and not p["baseline"].get("hit"))
    neither = sum(1 for p in usable if not p["baseline"].get("hit") and not p["guided"].get("hit"))

    # 两臂意见不一致的条数（与"是否命中标签"无关，衡量稳定性）
    disagree = sum(
        1 for p in usable
        if (p["baseline"].get("verdict"), p["baseline"].get("can_judge"))
        != (p["guided"].get("verdict"), p["guided"].get("can_judge"))
    )

    crit = {}
    for name in CRITERION_KEYWORDS:
        cb = sum(1 for p in usable if p["baseline"].get("criteria", {}).get(name))
        cg = sum(1 for p in usable if p["guided"].get("criteria", {}).get(name))
        crit[name] = {"baseline": cb, "guided": cg}

    def avg(arm, field):
        vals = [p[arm].get(field) for p in usable if isinstance(p[arm].get(field), (int, float))]
        return round(statistics.mean(vals), 2) if vals else None

    return {
        "n_usable": n,
        "n_skipped": skipped,
        "baseline_hits": b_hit,
        "guided_hits": g_hit,
        "baseline_acc": round(b_hit / n, 4) if n else 0.0,
        "guided_acc": round(g_hit / n, 4) if n else 0.0,
        "delta": round((g_hit - b_hit) / n, 4) if n else 0.0,
        "baseline_ci": wilson_interval(b_hit, n),
        "guided_ci": wilson_interval(g_hit, n),
        "mcnemar": {
            "both_hit": both,
            "only_baseline": only_b,
            "only_guided": only_g,
            "neither": neither,
            "p_value": mcnemar_exact(only_b, only_g),
        },
        "verdict_disagreement": disagree,
        "criteria": crit,
        "avg_seconds": {"baseline": avg("baseline", "seconds"), "guided": avg("guided", "seconds")},
        "avg_evidence": {"baseline": avg("baseline", "evidence_count"),
                         "guided": avg("guided", "evidence_count")},
        "avg_reasoning_chars": {"baseline": avg("baseline", "reasoning_chars"),
                                "guided": avg("guided", "reasoning_chars")},
    }


# ----------------------------------------------------------------------
# 报告
# ----------------------------------------------------------------------


def pct(x):
    return f"{x * 100:.1f}%"


def verdict_of(summary):
    """
    给出一句**有分寸**的结论。

    刻意不写"有效/无效"这种断言：样本量通常只有十几条，
    不足以支撑因果结论。这里只做"信号强弱"的机械分级，并强制提示局限。
    """
    m = summary["mcnemar"]
    p = m["p_value"]
    d = summary["delta"]
    if summary["n_usable"] < 5:
        return "样本过少，无法得出结论", "n < 5，仅能验证流程可用"
    if d == 0 and m["only_baseline"] == 0 and m["only_guided"] == 0:
        return "两臂完全一致，未观察到差异", "所有样本在两臂下结论相同"
    if p < 0.05 and d > 0:
        return "观察到正向信号（统计显著）", f"准确率 +{pct(d)}，McNemar p={p:.4f}"
    if p < 0.05 and d < 0:
        return "⚠️ 观察到负向信号（统计显著）", f"准确率 {pct(d)}，建议回退或调整指导内容"
    if d > 0:
        return "观察到正向信号，但统计上不显著", f"准确率 +{pct(d)}，McNemar p={p:.4f}（样本量不足以定论）"
    if d < 0:
        return "观察到负向信号，但统计上不显著", f"准确率 {pct(d)}，McNemar p={p:.4f}"
    return "两臂准确率相同", "差值 0，但逐条结论可能不同（见不一致条数）"


def write_report(summary, paired, dataset_size, generated_at):
    DOCS_DIR.mkdir(parents=True, exist_ok=True)
    out = DOCS_DIR / "分类提示词对照报告.md"
    verdict, reason = verdict_of(summary)
    s = summary

    lines = [
        "# 分类提示词对照评测报告（「健康」类）",
        "",
        f"> 生成时间：{generated_at}　|　样本：`eval/dataset.jsonl` 中 category=健康 的 {dataset_size} 条",
        f"> 原始数据：`eval/results/{RAW_NAME}.json`（可复核每一条的结论与推理链长度）",
        "",
        "## 0. 一句话结论",
        "",
        f"**{verdict}** —— {reason}",
        "",
        "> ⚠️ dataset.jsonl 是**作者自行标注的内部小样本**（并非公开基准），",
        "> 标签代表作者判断。样本量小，本报告只能发现**较大**的差异，",
        "> 不足以支撑「提示词有效/无效」的绝对结论。",
        "",
        "## 1. 设计",
        "",
        "同一条断言跑两次，**唯一差别是 category 传不传**：",
        "",
        "| 臂 | category | 系统提示词 |",
        "| --- | --- | --- |",
        "| Baseline（对照） | 不传 | 通用提示词 |",
        "| Guided（实验） | `健康` | 通用提示词 + 健康类核查重点 |",
        "",
        "配对设计：断言难度被抵消，比较的是同一批题在两个条件下的表现。",
        "两臂使用同一模型、同一搜索源。",
        "",
        "## 2. 主指标：准确率（verdict 命中人工标签）",
        "",
        "口径固定分母：拒答（can_judge=false）与 UNVERIFIED 都计为未命中。",
        "",
        "| 指标 | Baseline | Guided | 差值 |",
        "| --- | --- | --- | --- |",
        f"| 命中 / 有效样本 | {s['baseline_hits']} / {s['n_usable']} | {s['guided_hits']} / {s['n_usable']} | — |",
        f"| 准确率 | **{pct(s['baseline_acc'])}** | **{pct(s['guided_acc'])}** | **{'+' if s['delta'] >= 0 else ''}{pct(s['delta'])}** |",
        f"| 95% Wilson 区间 | [{pct(s['baseline_ci'][0])}, {pct(s['baseline_ci'][1])}] | "
        f"[{pct(s['guided_ci'][0])}, {pct(s['guided_ci'][1])}] | — |",
        "",
        f"未纳入统计的样本（单臂失败的）：{s['n_skipped']} 条",
        "",
        "### 2.1 配对四格表与 McNemar 精确检验",
        "",
        "| | Guided 命中 | Guided 未命中 |",
        "| --- | --- | --- |",
        f"| **Baseline 命中** | {s['mcnemar']['both_hit']} | {s['mcnemar']['only_baseline']} |",
        f"| **Baseline 未命中** | {s['mcnemar']['only_guided']} | {s['mcnemar']['neither']} |",
        "",
        f"* 只有 Guided 命中：**{s['mcnemar']['only_guided']}** 条",
        f"* 只有 Baseline 命中：**{s['mcnemar']['only_baseline']}** 条",
        f"* McNemar 精确检验双侧 **p = {s['mcnemar']['p_value']:.4f}**",
        "",
        "> 读法：真正的证据在「只有一臂命中」的那两格。若两者接近（例如 2 : 1），",
        "> 说明差异更像随机波动，而不是提示词的效果。",
        "",
        "### 2.2 稳定性",
        "",
        f"两臂结论**不一致**的样本：**{s['verdict_disagreement']} / {s['n_usable']}** 条。",
        "这一项与准确率无关：不一致多说明结论对提示词敏感（可能正向也可能负向），",
        "不一致少但准确率相同，说明提示词基本没影响到结论。",
        "",
        "## 3. 副指标：推理链是否命中关键判据（仅供线索）",
        "",
        "按关键词命中统计。**它衡量的是「模型有没有说出我们想让它说的东西」，",
        "不等于判断更正确** —— 模型可能本来就会说，只是措辞不同。",
        "",
        "| 关键判据 | Baseline 命中 | Guided 命中 | 差值 |",
        "| --- | --- | --- | --- |",
    ]
    for name, v in s["criteria"].items():
        d = v["guided"] - v["baseline"]
        lines.append(
            f"| {name} | {v['baseline']} / {s['n_usable']} | {v['guided']} / {s['n_usable']} | "
            f"{'+' if d >= 0 else ''}{d} |"
        )

    lines += [
        "",
        "## 4. 开销对照",
        "",
        "| 指标 | Baseline | Guided |",
        "| --- | --- | --- |",
        f"| 平均耗时（秒） | {s['avg_seconds']['baseline']} | {s['avg_seconds']['guided']} |",
        f"| 平均证据数 | {s['avg_evidence']['baseline']} | {s['avg_evidence']['guided']} |",
        f"| 平均推理链字数 | {s['avg_reasoning_chars']['baseline']} | {s['avg_reasoning_chars']['guided']} |",
        "",
        "> 推理链字数明显变长是**预期内**的（提示词要求核对更多点），",
        "> 但若耗时显著上升，需要权衡是否值得。",
        "",
        "## 5. 逐条结果",
        "",
        "| id | 人工标签 | Baseline | Guided | 是否一致 | 命中变化 |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for p in paired:
        b, g = p["baseline"], p["guided"]
        fb = b.get("verdict") if b.get("ok") else "失败"
        fg = g.get("verdict") if g.get("ok") else "失败"
        same = "✓" if (fb, b.get("can_judge")) == (fg, g.get("can_judge")) else "✗"
        if b.get("ok") and g.get("ok"):
            if b["hit"] and not g["hit"]:
                change = "**变差**"
            elif g["hit"] and not b["hit"]:
                change = "**变好**"
            else:
                change = "—"
        else:
            change = "n/a"
        lines.append(f"| {p['id']} | {p['label']} | {fb} | {fg} | {same} | {change} |")

    lines += [
        "",
        "## 6. 复现方式",
        "",
        "```powershell",
        "cd fact-agent",
        ".\\.venv\\Scripts\\python.exe eval\\category_ab.py --dry-run      # 零成本预检",
        ".\\.venv\\Scripts\\python.exe eval\\category_ab.py --subset 8     # 小规模试跑",
        ".\\.venv\\Scripts\\python.exe eval\\category_ab.py --all          # 全部健康类样本",
        ".\\.venv\\Scripts\\python.exe eval\\category_ab.py --report       # 只用已有结果重出报告",
        "```",
        "",
        "## 7. 这次评测**没有**回答的问题",
        "",
        "- 其余四个分类（社会/财经/科技/政策）的指导尚未编写，不在本次范围内；",
        "- 样本量不足以刻画「在哪些子类上更好」——例如「风险等级误用」类断言是否受益更多；",
        "- 未使用公开基准数据集，结论不能外推到真实分布。",
        "",
    ]
    out.write_text("\n".join(lines), encoding="utf-8")
    return out


# ----------------------------------------------------------------------
# 主流程
# ----------------------------------------------------------------------


def save_raw(payload):
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    (RESULTS_DIR / f"{RAW_NAME}.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=1), encoding="utf-8")


def load_raw():
    path = RESULTS_DIR / f"{RAW_NAME}.json"
    if not path.exists():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def main():
    parser = argparse.ArgumentParser(
        description="分类提示词 A/B 对照评测（回答「分类提示词到底有没有用」）")
    parser.add_argument("--subset", type=int, default=8,
                        help="抽样条数（默认 8；先用小样本验证流程与花费）")
    parser.add_argument("--all", action="store_true", help="跑全部健康类样本")
    parser.add_argument("--dry-run", action="store_true",
                        help="零成本预检：只打印样本与调用量估算，不联网、不调用 API")
    parser.add_argument("--report", action="store_true",
                        help="不跑评测，只用 eval/results 里已保存的结果重新出报告")
    parser.add_argument("--fresh", action="store_true",
                        help="忽略已保存的进度，从头重跑（会重复消耗 API 额度）")
    args = parser.parse_args()

    all_rows = load_dataset()
    health = [r for r in all_rows if r.get("category") == TARGET_CATEGORY]

    # 抽样：按 id 排序后等距取，保证覆盖数据集不同区段（而非只取前 N 条）
    if args.all:
        selected = health
    else:
        n = max(1, min(args.subset, len(health)))
        step = len(health) / n
        selected = [health[int(i * step)] for i in range(n)]

    if args.dry_run:
        print("=" * 74)
        print("  零成本预检（未联网、未调用任何 API）")
        print("=" * 74)
        print(f"  数据集总条数        : {len(all_rows)}")
        print(f"  其中 category=健康  : {len(health)}")
        print(f"  本次将评测          : {len(selected)} 条"
              f"{'（全部）' if args.all else f'（等距抽样，subset={args.subset}）'}")
        print()
        print("  将执行的检测调用次数（两臂合计）：")
        print(f"    基线（不传 category） : {len(selected)} 次完整检测链路")
        print(f"    实验（ category=健康）: {len(selected)} 次完整检测链路")
        print(f"    合计                  : {len(selected) * 2} 次"
              "（每次含 1 次 LLM 生成搜索词 + 1~3 次搜索 + 若干网页抓取 + 1 次 LLM 判定）")
        print()
        print("  ⚠️ 这会产生真实的 API 费用。建议先 --subset 4 确认流程与单价，再放大。")
        print()
        print("  样本预览（前 8 条）：")
        for r in selected[:8]:
            print(f"    id={r['id']:<3} label={r['label']:<10} {r['text'][:44]}")
        if len(selected) > 8:
            print(f"    …… 其余 {len(selected) - 8} 条省略")
        print()
        print("  配置确认：")
        print(f"    LLM_MODEL   = {settings.LLM_MODEL}")
        print(f"    SEARCH_TOOL = {settings.SEARCH_TOOL}")
        print(f"    LLM_API_KEY 已配置 = {bool(settings.LLM_API_KEY)}")
        print(f"    SEARCH_API_KEY 已配置 = {bool(settings.SEARCH_API_KEY)}")
        if not settings.LLM_API_KEY or not settings.SEARCH_API_KEY:
            print()
            print("  ❌ 缺少 API Key，无法执行真实评测（--dry-run 之外的模式会失败）")
        return 0

    if args.report:
        raw = load_raw()
        if not raw:
            print("没有已保存的结果，先跑一次评测再 --report")
            return 1
        out = write_report(raw["summary"], raw["paired"], raw["dataset_size"], raw["generated_at"])
        print(f"报告已重写：{out}")
        return 0

    if not settings.LLM_API_KEY:
        print("❌ 未配置 LLM_API_KEY，无法评测。请先设置 fact-agent/.env 后重试。")
        return 1

    # 断点续跑：把已完成的结果载入内存，重跑时跳过
    done = {}
    if not args.fresh:
        raw = load_raw()
        if raw:
            for p in raw.get("paired", []):
                for arm in ("baseline", "guided"):
                    if p.get(arm, {}).get("ok"):
                        done[(p["id"], arm)] = p[arm]
            if done:
                print(f"（沿用已保存的 {len(done)} 条结果；要全部重跑请加 --fresh）")

    service = build_service()
    paired = []
    started_at = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    total = len(selected) * 2
    step_no = 0

    for row in selected:
        entry = {"id": row["id"], "label": row["label"], "text": row["text"]}
        for arm, category in (("baseline", None), ("guided", TARGET_CATEGORY)):
            step_no += 1
            cached = done.get((row["id"], arm))
            if cached:
                entry[arm] = cached
                print(f"  [{step_no}/{total}] id={row['id']} {arm:<8} 沿用缓存")
                continue
            print(f"  [{step_no}/{total}] id={row['id']} {arm:<8} 调用中…", end="", flush=True)
            entry[arm] = run_one(service, row["text"], category, row["label"])
            r = entry[arm]
            flag = "命中" if r.get("hit") else ("失败" if not r.get("ok") else "未命中")
            print(f" verdict={r.get('verdict')} {flag} ({r.get('seconds')}s)")
        paired.append(entry)

    summary = summarize(paired)
    generated_at = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    save_raw({
        "mode": "category_ab",
        "target_category": TARGET_CATEGORY,
        "dataset_size": len(health),
        "started_at": started_at,
        "generated_at": generated_at,
        "summary": summary,
        "paired": paired,
    })
    out = write_report(summary, paired, len(health), generated_at)

    print()
    print("=" * 74)
    print(f"  有效样本 {summary['n_usable']} 条")
    print(f"  Baseline 命中 {summary['baseline_hits']} / {summary['n_usable']}"
          f"  ({pct(summary['baseline_acc'])})")
    print(f"  Guided   命中 {summary['guided_hits']} / {summary['n_usable']}"
          f"  ({pct(summary['guided_acc'])})")
    print(f"  差值 {pct(summary['delta'])}　McNemar p = {summary['mcnemar']['p_value']:.4f}")
    verdict, why = verdict_of(summary)
    print(f"  → {verdict}（{why}）")
    print("=" * 74)
    print(f"报告：{out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
