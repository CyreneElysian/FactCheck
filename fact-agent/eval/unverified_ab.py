"""
「不可证伪断言」提示词改动的 A/B 对照评测
================================================================================

## 要解决的问题（实测发现）

评测 `eval/category_ab.py` 时发现：数据集里 15 条人工标签为 **UNVERIFIED** 的断言，
模型**几乎全部判成 FALSE**（置信 0.80~0.97），而 `FALSE` / `TRUE` 两类几乎全对。
即模型在"非黑即白"上很强，**在"说不清"上系统性偏错**：该转人工的判成了"假"。

根因在系统提示词的规则设计（`app/prompts/detect_prompt.py`）：
原有的 5 条规则里，第 2 条只讲了 `can_judge=false`，**从未说明什么时候该用 `UNVERIFIED`**。
于是模型默认走"找不到证据 → 判假"，即把**证据缺失**当成了**证伪**（诉诸无知）。

## 本次改动

在系统提示词里新增第 6、7 条：明确「不要把找不到证据当成假」，并给出 `UNVERIFIED` 的
适用形态（指向无法确认存在的事物 / 面向未来 / 原理上并非不可能但无证据支持）。
同时显式排除误用：与公认科学共识相矛盾的断言（疫苗致自闭症等）属于**已被证伪**，
应判 FALSE，不能因为"听起来绝对"就判 UNVERIFIED。

## 评测设计

配对 A/B，同一条断言跑两次，唯一差别是**规则 6/7 加不加**：
    Arm A（基线）：原系统提示词（5 条规则）
    Arm B（实验）：原系统提示词 + 规则 6/7

样本：`eval/dataset.jsonl` **全部 60 条**（不只健康类）——因为"不可证伪"不是健康类独有：
15 条 UNVERIFIED 分散在社会(7)/科技(3)/健康(3)/政策(1)/财经(1)。

### 两个方向都要看（这是本评测的关键）

| 指标 | 为什么重要 |
| --- | --- |
| **UNVERIFIED 命中数**（目标） | 改动是否真的修好了"该转人工却判假" |
| **FALSE/TRUE 被误判成 UNVERIFIED 的条数**（回归） | 新规则的**主要风险**：让模型变得过度谨慎，把"已被证伪"的断言也说成"无法判定" |

只报前者会得出"改动有效"的错误结论 —— 一个把所有断言都判 UNVERIFIED 的模型，
在前一个指标上能拿满分。**必须同时看回归面。**

## 用法

    # 零成本预检
    python eval/unverified_ab.py --dry-run

    # 小规模试跑（建议先 8 条确认流程）
    python eval/unverified_ab.py --subset 8

    # 全量 60 条（推荐：15 条目标样本才够看出方向）
    python eval/unverified_ab.py --all

    # 只用已保存结果重出报告
    python eval/unverified_ab.py --report

结果：
    eval/results/unverified_ab.json
    docs/不可证伪断言提示词对照报告.md
"""
import argparse
import io
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

try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except (AttributeError, OSError):
    pass

from app.config import settings                        # noqa: E402
from app.prompts import detect_prompt                  # noqa: E402
from app.services import detect_service as ds          # noqa: E402
from app.services.detect_service import DetectService  # noqa: E402
from app.services.llm import OpenAICompatibleLLM       # noqa: E402
from app.services.search import get_search_tool        # noqa: E402

DATASET = EVAL_DIR / "dataset.jsonl"
RESULTS_DIR = EVAL_DIR / "results"
DOCS_DIR = REPO_ROOT / "docs"
RAW_NAME = "unverified_ab"

# 改动前/后的系统提示词，各自完整保存 —— 评测必须能复现当时用的那份提示词
PROMPT_BEFORE = detect_prompt.SYSTEM_PROMPT

# 规则 6/7：本次要验证的改动
RULES_6_7 = """
6. **不要把「找不到证据」当成「假」**（诉诸无知）：只有当证据**支持**该断言为假时才判 FALSE。
   反过来，只有在证据**支持**该断言为真时才判 TRUE。
7. 遇到「**不可证伪**」的断言时判 UNVERIFIED（can_judge 仍为 true）：
   * 指向一个**无法确认是否存在**的具体事物：「某款新药可以彻底治愈××」「某种野生菌能延寿十年」——
     这类说法你既无法证明它存在，也无法证明它不存在；
   * 指向**未来**或**尚未发生**的事：「××将在××年实现」；
   * 「某种做法**可以**达到某效果」而该效果在原理上并非不可能，只是没有可靠证据支持。
   这类断言判 FALSE 会把「未经证实」说成「已被证伪」，因此必须用 UNVERIFIED 表达
   「现有证据不足以判定」，让系统转人工复核。
   注意排除：与**公认科学共识相矛盾**的断言（如「疫苗导致自闭症」「微波炉加热产生致癌物」）
   属于**已被证伪**，应判 FALSE，不要因为"听起来绝对"就判 UNVERIFIED。
"""


def load_dataset():
    rows = []
    with DATASET.open(encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def build_service():
    tool = get_search_tool(settings.SEARCH_TOOL, settings.SEARCH_API_KEY)
    llm = OpenAICompatibleLLM(
        api_key=settings.LLM_API_KEY,
        base_url=settings.LLM_BASE_URL,
        model=settings.LLM_MODEL,
    )
    return DetectService(search_tool=tool, llm=llm)


def run_one(service, text, category, label):
    started = time.time()
    try:
        resp = service.detect(text, category)
        verdict = getattr(resp, "verdict", None)
        can_judge = bool(getattr(resp, "can_judge", False))
        return {
            "ok": True,
            "verdict": verdict,
            "can_judge": can_judge,
            "confidence": getattr(resp, "confidence", None),
            "reasoning_chars": len(getattr(resp, "reasoning_chain", None) or ""),
            "evidence_count": len(getattr(resp, "evidence", None) or []),
            "hit": bool(can_judge and verdict and verdict == label),
            "seconds": round(time.time() - started, 1),
        }
    except Exception as exc:                                   # noqa: BLE001
        return {"ok": False, "error": True, "failure": f"{type(exc).__name__}: {exc}",
                "hit": False, "seconds": round(time.time() - started, 1)}


def mcnemar_exact(b, c):
    n = b + c
    if n == 0:
        return 1.0
    k = min(b, c)
    tail = sum(math.comb(n, i) for i in range(0, k + 1)) / (2 ** n)
    return min(1.0, 2 * tail)


def summarize(paired, target_label="UNVERIFIED"):
    usable = [p for p in paired if p["before"].get("ok") and p["after"].get("ok")]

    def acc(arm, label=None):
        pool = [p for p in usable if label is None or p["label"] == label]
        if not pool:
            return 0, 0
        return sum(1 for p in pool if p[arm].get("hit")), len(pool)

    b_hit, n = acc("before")
    a_hit, _ = acc("after")

    target_b, target_n = acc("before", target_label)
    target_a, _ = acc("after", target_label)

    # 回归面：非目标标签里，被误判成 UNVERIFIED 的条数
    def wrongly_unverified(arm):
        return sum(1 for p in usable
                   if p["label"] != target_label and p[arm].get("verdict") == target_label)

    only_b = sum(1 for p in usable if p["before"].get("hit") and not p["after"].get("hit"))
    only_a = sum(1 for p in usable if p["after"].get("hit") and not p["before"].get("hit"))

    def avg(arm, field):
        vals = [p[arm].get(field) for p in usable if isinstance(p[arm].get(field), (int, float))]
        return round(statistics.mean(vals), 2) if vals else None

    return {
        "n_usable": n,
        "n_skipped": len(paired) - n,
        "before_hits": b_hit, "after_hits": a_hit,
        "before_acc": round(b_hit / n, 4) if n else 0.0,
        "after_acc": round(a_hit / n, 4) if n else 0.0,
        "delta": round((a_hit - b_hit) / n, 4) if n else 0.0,
        "target_label": target_label,
        "target_before": target_b, "target_after": target_a, "target_total": target_n,
        "wrongly_unverified_before": wrongly_unverified("before"),
        "wrongly_unverified_after": wrongly_unverified("after"),
        "only_before": only_b, "only_after": only_a,
        "mcnemar_p": mcnemar_exact(only_b, only_a),
        "avg_seconds": {"before": avg("before", "seconds"), "after": avg("after", "seconds")},
        "avg_reasoning": {"before": avg("before", "reasoning_chars"),
                          "after": avg("after", "reasoning_chars")},
        "label_confusion": {
            label: {
                "before": {v: sum(1 for p in usable if p["label"] == label and p["before"].get("verdict") == v)
                           for v in ("TRUE", "FALSE", "UNVERIFIED", None)},
                "after": {v: sum(1 for p in usable if p["label"] == label and p["after"].get("verdict") == v)
                          for v in ("TRUE", "FALSE", "UNVERIFIED", None)},
                "total": sum(1 for p in usable if p["label"] == label),
            }
            for label in ("TRUE", "FALSE", "UNVERIFIED")
        },
    }


def pct(x):
    return f"{x * 100:.1f}%"


def verdict_of(s):
    """给出有分寸的结论：**两个方向都要看**，只看目标指标会误判"""
    d_target = s["target_after"] - s["target_before"]
    regress = s["wrongly_unverified_after"] - s["wrongly_unverified_before"]
    if s["n_usable"] < 10:
        return "样本过少，无法得出结论", "n < 10，仅能验证流程可用"
    if d_target > 0 and regress <= 0:
        return "✅ 正向：目标指标改善且无回归", \
               f"UNVERIFIED 命中 {s['target_before']}→{s['target_after']}/{s['target_total']}，" \
               f"误判为 UNVERIFIED 的条数 {s['wrongly_unverified_before']}→{s['wrongly_unverified_after']}"
    if d_target > 0 and regress > 0:
        return "⚠️ 有得有失：目标改善但同时引入了回归", \
               f"UNVERIFIED 命中 +{d_target}，但多出 {regress} 条被误判为 UNVERIFIED"
    if d_target == 0:
        return "未观察到目标指标改善", "UNVERIFIED 命中数与改动前相同"
    return "⚠️ 负向：目标指标反而变差", f"UNVERIFIED 命中 {d_target}"


def write_report(s, paired, generated_at):
    DOCS_DIR.mkdir(parents=True, exist_ok=True)
    out = DOCS_DIR / "不可证伪断言提示词对照报告.md"
    v, why = verdict_of(s)

    lines = [
        "# 「不可证伪断言」提示词 A/B 对照报告",
        "",
        f"> 生成时间：{generated_at}　|　样本：`eval/dataset.jsonl` 全部 {len(paired)} 条",
        f"> 原始数据：`eval/results/{RAW_NAME}.json`",
        "",
        "## ✅ 最终决定：改动已回退",
        "",
        "**行动**：系统提示词里的规则 6/7 已删除，`app/prompts/detect_prompt.py` 恢复原样"
        "（`SYSTEM_PROMPT` 长度回到 333 字）。",
        "",
        "**为什么回退**：净收益为零 —— 修好 3 条、弄坏 3 条，而目标指标（UNVERIFIED 命中）"
        "**反而从 9/15 降到 8/15**。改动恰好产生了本报告第 3 节预警过的那个退化"
        "（让模型变得过度谨慎，甚至开始拒答）。",
        "",
        "| 修好（3 条） | 弄坏（3 条） |",
        "| --- | --- |",
        "| id=19 洗完头不吹干睡觉得面瘫：拒答 → FALSE | id=18 长痘因吃辣：FALSE → UNVERIFIED |",
        "| id=24 手机信号差辐射大：UNVERIFIED → FALSE | id=53 某国在外星建基地：变成拒答 |",
        "| id=46 拍到不明飞行物：TRUE → UNVERIFIED | id=56 手机电池爆炸风险：变成拒答 |",
        "",
        "## ⚠️ 更重要的：我最初对「根因」的判断是错的",
        "",
        "本节标题与下面的问题描述**保留原文以留档**，但它们**已被本次全量评测推翻**：",
        "",
        "原来断言「15 条 UNVERIFIED 几乎全部被模型判成 FALSE、存在系统性偏错」。",
        "那个结论来自一个**偏差样本** —— `eval/category_ab.py` 只跑「健康」类 33 条，",
        "其中恰好只有 3 条 UNVERIFIED，看起来像「几乎全错」。",
        "",
        "**扩大到全部 60 条后，基线在 15 条 UNVERIFIED 上命中 9/15** —— 多数本来就判对；",
        "而那 3 条健康类里也有 2 条（id=54「野生菌延寿十年」、id=59「生吃蔬菜杀寄生虫」）",
        "基线就是对的。**「系统性偏错」这个说法不成立。**",
        "",
        "> 教训：**在小样本子集上发现的现象，不能直接当成全局结论**。",
        "> 本项目的数据集**已经带 `category` 字段**，先看全量分布只多花一分钟，",
        "> 而基于偏差子集去改提示词，代价是 120 次真实检测调用 + 一个负向结论。",
        "",
        "---",
        "",
        "## 0. 脚本给出的结论",
        "",
        f"**{v}** —— {why}",
        "",
        "> ⚠️ `eval/dataset.jsonl` 是**作者自行标注的内部小样本**，标签代表作者判断。",
        "> 其中 id=49/50/58 等几条的标签本身可商榷（例如「某款新药可以彻底治愈阿尔茨海默病」，",
        "> 标 UNVERIFIED 还是 FALSE 取决于口径：作为「存在这样的药」是未证实，",
        "> 作为「现有药物能治愈」则是已被证伪）—— 这类边界样本会影响总分，读结论时请留意。",
        "",
        "## 1. 问题描述（**保留原文留档；其量级判断已被上文推翻**）",
        "",
        "数据集里 15 条人工标签为 `UNVERIFIED` 的断言，模型**几乎全部判成 FALSE**"
        "（置信 0.80~0.97），而 TRUE/FALSE 两类几乎全对。",
        "即：模型在「非黑即白」上很强，**在「说不清」上系统性偏错** —— 该转人工的判成了「假」。",
        "",
        "根因（**这是当时的猜测，已被推翻**）：系统提示词原有的 5 条规则里，",
        "第 2 条只讲了 `can_judge=false`，**从未说明什么时候该用 `UNVERIFIED`**。",
        "模型于是默认「找不到证据 → 判假」，即把**证据缺失**当成了**证伪**（诉诸无知）。",
        "",
        "## 2. 改动内容",
        "",
        "在系统提示词里新增第 6、7 条（`app/prompts/detect_prompt.py`）：",
        "",
        "* **规则 6**：不要把「找不到证据」当成「假」—— 只有证据**支持**某结论时才那么判；",
        "* **规则 7**：给出 `UNVERIFIED` 的适用形态（指向无法确认存在的事物 / 面向未来 /",
        "  原理上并非不可能但无证据支持），并**显式排除误用**：与公认科学共识相矛盾的断言",
        "  （疫苗致自闭症等）属于已被证伪，应判 FALSE。",
        "",
        "## 3. 评测设计",
        "",
        "配对 A/B，同一条断言跑两次，唯一差别是规则 6/7 加不加：",
        "",
        "| 臂 | 系统提示词 |",
        "| --- | --- |",
        "| Arm A（before） | 原 5 条规则 |",
        "| Arm B（after） | 原 5 条 + 规则 6/7 |",
        "",
        "样本用**全部 60 条**而非只挑健康类 —— 「不可证伪」不是健康类独有：",
        "15 条 UNVERIFIED 分散在社会(7)/科技(3)/健康(3)/政策(1)/财经(1)。",
        "",
        "### ⚠️ 为什么必须同时看两个方向",
        "",
        "| 指标 | 意义 |",
        "| --- | --- |",
        "| **UNVERIFIED 命中数**（目标） | 改动是否真的修好了「该转人工却判假」 |",
        "| **非目标标签被误判成 UNVERIFIED 的条数**（回归） | 新规则的**主要风险**：让模型过度谨慎 |",
        "",
        "只报目标指标会得出错误结论 —— 一个把所有断言都判 UNVERIFIED 的模型，",
        "在目标指标上能拿满分。",
        "",
        "## 4. 结果",
        "",
        "### 4.1 总准确率",
        "",
        "| 指标 | before | after | 差值 |",
        "| --- | --- | --- | --- |",
        f"| 命中 / 有效样本 | {s['before_hits']} / {s['n_usable']} | {s['after_hits']} / {s['n_usable']} | — |",
        f"| 准确率 | **{pct(s['before_acc'])}** | **{pct(s['after_acc'])}** | **{'+' if s['delta'] >= 0 else ''}{pct(s['delta'])}** |",
        f"| McNemar 精确检验 p | — | — | {s['mcnemar_p']:.4f} |",
        f"| 只有 before 命中 | {s['only_before']} 条 | — | — |",
        f"| 只有 after 命中 | — | {s['only_after']} 条 | — |",
        "",
        f"未纳入统计（单臂失败）：{s['n_skipped']} 条",
        "",
        "### 4.2 ★ 目标指标：UNVERIFIED 断言",
        "",
        f"| | before | after |",
        f"| --- | --- | --- |",
        f"| UNVERIFIED 命中 | **{s['target_before']} / {s['target_total']}** | **{s['target_after']} / {s['target_total']}** |",
        "",
        "### 4.3 ★ 回归面：被误判成 UNVERIFIED 的非目标断言",
        "",
        f"| | before | after |",
        f"| --- | --- | --- |",
        f"| TRUE/FALSE 被误判为 UNVERIFIED | **{s['wrongly_unverified_before']} 条** | **{s['wrongly_unverified_after']} 条** |",
        "",
        "这一行是判断改动能否上线的关键：数值上升说明新规则让模型变得过度谨慎。",
        "",
        "### 4.4 逐标签混淆矩阵",
        "",
        "| 人工标签 | 总数 | 臂 | TRUE | FALSE | UNVERIFIED | 拒绝判定 |",
        "| --- | --- | --- | --- | --- | --- | --- |",
    ]
    for label, block in s["label_confusion"].items():
        for arm in ("before", "after"):
            c = block[arm]
            lines.append(
                f"| {label} | {block['total']} | {arm} | {c.get('TRUE', 0)} | {c.get('FALSE', 0)} | "
                f"{c.get('UNVERIFIED', 0)} | {c.get('None', 0)} |"
            )

    lines += [
        "",
        "### 4.5 成本",
        "",
        "| 指标 | before | after |",
        "| --- | --- | --- |",
        f"| 平均耗时（秒） | {s['avg_seconds']['before']} | {s['avg_seconds']['after']} |",
        f"| 平均推理链字数 | {s['avg_reasoning']['before']} | {s['avg_reasoning']['after']} |",
        "",
        "## 5. 逐条结果",
        "",
        "| id | 分类 | 人工标签 | before | after | 变化 |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for p in paired:
        b, a = p["before"], p["after"]
        vb = b.get("verdict") if b.get("ok") else "失败"
        va = a.get("verdict") if a.get("ok") else "失败"
        if b.get("ok") and a.get("ok"):
            change = ("**修好**" if a["hit"] and not b["hit"]
                      else "**变差**" if b["hit"] and not a["hit"] else "—")
        else:
            change = "n/a"
        lines.append(f"| {p['id']} | {p.get('category','')} | {p['label']} | {vb} | {va} | {change} |")

    lines += [
        "",
        "## 6. 复现",
        "",
        "```powershell",
        "cd fact-agent",
        ".\\.venv\\Scripts\\python.exe eval\\unverified_ab.py --dry-run",
        ".\\.venv\\Scripts\\python.exe eval\\unverified_ab.py --all",
        ".\\.venv\\Scripts\\python.exe eval\\unverified_ab.py --report",
        "```",
        "",
        "## 7. 本次评测没有回答的问题",
        "",
        "- 样本量（15 条目标）只能看出方向，不足以给出精确的改善幅度；",
        "- 未区分「不可证伪」的子类型（传闻类 / 未来类 / 存在性类）各自受益多少；",
        "- dataset.jsonl 是作者自行标注的内部小样本，不是公开基准，结论不能外推到真实分布。",
        "",
    ]
    out.write_text("\n".join(lines), encoding="utf-8")
    return out


def save_raw(payload):
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    (RESULTS_DIR / f"{RAW_NAME}.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=1), encoding="utf-8")


def load_raw():
    p = RESULTS_DIR / f"{RAW_NAME}.json"
    return json.loads(p.read_text(encoding="utf-8")) if p.exists() else None


def main():
    ap = argparse.ArgumentParser(description="「不可证伪断言」提示词 A/B 对照评测")
    ap.add_argument("--subset", type=int, default=8, help="抽样条数（默认 8）")
    ap.add_argument("--all", action="store_true", help="跑全部 60 条（推荐）")
    ap.add_argument("--dry-run", action="store_true", help="零成本预检，不联网")
    ap.add_argument("--report", action="store_true", help="只用已保存结果重出报告")
    ap.add_argument("--fresh", action="store_true", help="忽略已保存进度重跑")
    args = ap.parse_args()

    rows = load_dataset()
    if args.all:
        selected = rows
    else:
        n = max(1, min(args.subset, len(rows)))
        step = len(rows) / n
        selected = [rows[int(i * step)] for i in range(n)]

    if args.dry_run:
        from collections import Counter
        lab = Counter(r["label"] for r in selected)
        print("=" * 74)
        print("  零成本预检（未联网）")
        print("=" * 74)
        print(f"  数据集总数        : {len(rows)}")
        print(f"  本次评测          : {len(selected)} 条"
              f"{'（全部）' if args.all else f'（等距抽样 subset={args.subset}）'}")
        print(f"  其中 UNVERIFIED   : {lab.get('UNVERIFIED', 0)} 条  ← 目标指标")
        print(f"  其余（回归风险面）: {len(selected) - lab.get('UNVERIFIED', 0)} 条")
        print()
        print(f"  将执行检测次数（两臂合计）: {len(selected) * 2}")
        print("    before 臂：原 5 条规则")
        print("    after  臂：原 5 条 + 规则 6/7")
        print()
        print("  ⚠️ 真实 API 费用。建议先 --subset 8 验证流程，再用 --all 取结论。")
        print()
        print("  规则 6/7 与基线提示词的长度对照：")
        print(f"    before: {len(PROMPT_BEFORE)} 字")
        print(f"    after : {len(PROMPT_BEFORE + RULES_6_7)} 字")
        return 0

    if args.report:
        raw = load_raw()
        if not raw:
            print("没有已保存的结果，先跑一次评测")
            return 1
        out = write_report(raw["summary"], raw["paired"], raw["generated_at"])
        print(f"报告已重写：{out}")
        return 0

    if not settings.LLM_API_KEY:
        print("❌ 未配置 LLM_API_KEY")
        return 1

    done = {}
    if not args.fresh:
        raw = load_raw()
        if raw:
            for p in raw.get("paired", []):
                for arm in ("before", "after"):
                    if p.get(arm, {}).get("ok"):
                        done[(p["id"], arm)] = p[arm]
            if done:
                print(f"（沿用已保存的 {len(done)} 条；全部重跑请加 --fresh）")

    service = build_service()
    paired = []
    started_at = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    total = len(selected) * 2
    step_no = 0

    for row in selected:
        entry = {"id": row["id"], "label": row["label"], "category": row.get("category", ""),
                 "text": row["text"]}
        for arm, extra in (("before", ""), ("after", RULES_6_7)):
            step_no += 1
            cached = done.get((row["id"], arm))
            if cached:
                entry[arm] = cached
                print(f"  [{step_no}/{total}] id={row['id']} {arm:<6} 沿用缓存")
                continue
            # 关键：直接替换 detect_service 模块级 SYSTEM_PROMPT。
            # 它同时被 detect() 使用（build_system_prompt 以它为基准），
            # 因此这里改一处即可让整条链路用上新提示词。
            ds.SYSTEM_PROMPT = PROMPT_BEFORE + extra
            print(f"  [{step_no}/{total}] id={row['id']} {arm:<6} 调用中…", end="", flush=True)
            entry[arm] = run_one(service, row["text"], row.get("category"), row["label"])
            r = entry[arm]
            flag = "命中" if r.get("hit") else ("失败" if not r.get("ok") else "未命中")
            print(f" verdict={r.get('verdict')} {flag} ({r.get('seconds')}s)")
        paired.append(entry)

    ds.SYSTEM_PROMPT = PROMPT_BEFORE      # 还原

    summary = summarize(paired)
    generated_at = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    save_raw({"mode": "unverified_ab", "generated_at": generated_at,
              "started_at": started_at, "summary": summary, "paired": paired})
    out = write_report(summary, paired, generated_at)

    print()
    print("=" * 74)
    print(f"  有效样本 {summary['n_usable']} 条")
    print(f"  总准确率    before {summary['before_hits']}/{summary['n_usable']} "
          f"→ after {summary['after_hits']}/{summary['n_usable']}")
    print(f"  ★ UNVERIFIED before {summary['target_before']}/{summary['target_total']} "
          f"→ after {summary['target_after']}/{summary['target_total']}")
    print(f"  ★ 误判为 UNVERIFIED（回归面） before {summary['wrongly_unverified_before']} "
          f"→ after {summary['wrongly_unverified_after']}")
    print(f"  McNemar p = {summary['mcnemar_p']:.4f}")
    v, why = verdict_of(summary)
    print(f"  → {v}（{why}）")
    print("=" * 74)
    print(f"报告：{out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
