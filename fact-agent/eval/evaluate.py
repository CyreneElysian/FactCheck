"""
FactCheck AI 能力评测脚本

两种模式：

1) 离线去重评测（不需要任何 API Key，只用到本地 embedding 模型）
     py -3.10 fact-agent/eval/evaluate.py --mode dedup

   评测方法（注意：不能拿"已入库的原文"当查询，否则每次都会命中自己）：
   - 索引：用 60 条标注样本的**原文**构建 FAISS 索引；
   - 正例查询：每条样本的**同义改写**（不在索引中，属留出查询），
     期望 top-1 命中**自己那条**原文：
       * score >= 阈值 且 matched_id == 自己  -> 命中（hit）
       * score >= 阈值 但 matched_id != 自己  -> **误配（wrong）**，比漏判更危险
       * score < 阈值                          -> 漏判（miss）
   - 负例查询：一组**域外问题**（与数据集无关的日常问题），期望全部不命中：
       * score >= 阈值 -> 误报（FP）
   - 对阈值 0.80 ~ 0.96 做扫描，输出命中率 / 误配率 / 误报率曲线与报告。

2) 联网判定评测（需要 LLM_API_KEY 与可用的搜索源）
     py -3.10 fact-agent/eval/evaluate.py --mode live --subset 10
   对分层抽样出的样本真实调用检测链路，比较模型结论与标注标签，
   统计准确率、拒答率、失败率、平均耗时与平均证据数。

结果写入 docs/评测报告.md（覆盖）与 eval/results/*.json（原始数据，可复核）。

⚠️ 重要局限：dataset.jsonl 是**作者自行标注的内部小样本**（60 条），
   不是公开基准数据集；标签代表作者判断，仅用于回归对比与调参参考。
"""
import argparse
import json
import pathlib
import statistics
import sys
import time
from datetime import datetime

# 允许从仓库任意位置运行：把 fact-agent 加入 sys.path
EVAL_DIR = pathlib.Path(__file__).resolve().parent
FACT_AGENT = EVAL_DIR.parent
REPO_ROOT = FACT_AGENT.parent
sys.path.insert(0, str(FACT_AGENT))

from app.config import settings                      # noqa: E402
from app.services.dedup_service import DedupService  # noqa: E402
from app.services.detect_service import DetectService  # noqa: E402
from app.services.embedding import EmbeddingService  # noqa: E402
from app.services.llm import OpenAICompatibleLLM     # noqa: E402
from app.services.search import get_search_tool      # noqa: E402

DATASET = EVAL_DIR / "dataset.jsonl"
RESULTS_DIR = EVAL_DIR / "results"
DOCS_DIR = REPO_ROOT / "docs"
THRESHOLDS = [0.50, 0.55, 0.60, 0.65, 0.70, 0.75, 0.80, 0.84, 0.88, 0.92, 0.96]

# 域外查询：与数据集内容无关的日常问题，用于测"误报"
OUT_OF_DOMAIN_QUERIES = [
    "请问附近哪家火锅店好吃",
    "明天股市会涨还是跌",
    "如何安装 Node.js 开发环境",
    "推荐几本适合入门的算法书",
    "周末去哪个公园比较合适",
    "Python 里怎么读写 CSV 文件",
    "帮我写一封请假邮件",
    "这台笔记本的散热表现怎么样",
    "北京到上海坐高铁要多久",
    "怎么把照片的背景换成白色",
    "想学吉他应该先练什么",
    "公司年会的节目怎么安排",
    "这个月的电费为什么这么高",
    "怎样去除衣服上的油渍",
    "有没有适合新手的理财产品",
]

# 困难负例：与数据集某些条目**关键词高度重合但问的是另一件事**。
# 这类查询才是误配的真实风险来源——正确的去重不应该把它们判成同一个问题。
# 例：数据集有"喝醋可以预防感冒"，而"喝醋能软化血管吗"是另一个问题，不该复用前者的结论。
HARD_NEGATIVE_QUERIES = [
    "喝醋能软化血管吗",
    "疫苗有哪些常见不良反应",
    "微波炉加热食物会损失营养吗",
    "隔夜菜到底能不能吃",
    "5G 网络的下载速度有多快",
    "塑料回收标志怎么看",
    "一罐可乐的含糖量是多少",
    "香蕉的钾含量高不高",
    "痘痘肌日常怎么护理",
    "隐形眼镜每天最多戴几个小时",
    "国际空间站由几个舱段组成",
    "怎么查询自己的社保缴费记录",
]


def load_dataset():
    items = []
    with open(DATASET, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                items.append(json.loads(line))
    return items


# ----------------------------------------------------------------------
# 离线：语义去重评测
# ----------------------------------------------------------------------

def run_dedup(items, verbose=True):
    min_len = settings.DEDUP_MIN_TEXT_LENGTH

    # 只有长度达标的原文才会进索引；改写句本身若也短于 min_text_length，
    # 检索会被直接短路（返回 0 分），因此这两类都要单独统计，不计入指标分母。
    indexed = [it for it in items if len(it["text"].strip()) >= min_len]
    too_short = [it for it in items if len(it["text"].strip()) < min_len]
    queryable = [it for it in indexed if len(it["paraphrase"].strip()) >= min_len]
    query_too_short = [it for it in indexed if len(it["paraphrase"].strip()) < min_len]
    if verbose:
        print(f"数据集 {len(items)} 条：可入索引 {len(indexed)} 条"
              f"（因 min_text_length={min_len} 未入索引 {len(too_short)} 条）；"
              f"可用于语义检索的改写句 {len(queryable)} 条"
              f"（改写句过短被短路 {len(query_too_short)} 条）")

    tmp_dir = RESULTS_DIR / "_tmp_index"
    if tmp_dir.exists():
        for p in tmp_dir.iterdir():
            p.unlink()
    tmp_dir.mkdir(parents=True, exist_ok=True)

    print(f"加载 embedding 模型：{settings.EMBEDDING_MODEL}")
    embedding = EmbeddingService(settings.EMBEDDING_MODEL)
    dedup = DedupService(embedding_service=embedding, data_dir=tmp_dir,
                         threshold=settings.DEDUP_THRESHOLD,
                         min_text_length=min_len, top_k=1)

    t0 = time.time()
    for it in indexed:
        dedup.add(it["id"], it["text"])
    print(f"索引构建完成：ntotal={dedup._index.ntotal}，耗时 {time.time() - t0:.1f}s")

    # 关键：DedupService.search() 会用"配置阈值"在内部过滤，低于阈值的会直接返回 matched=False / id=None，
    # 这样就没法做阈值扫描了。这里临时把阈值关掉（-1），拿到不做过滤的原始 top-1，再在指标里自行套用各档阈值。
    original_threshold = dedup.threshold
    dedup.threshold = -1.0

    print("检索同义改写（正例）...")
    pos_hits = []
    for it in queryable:
        _, matched_id, score, _ = dedup.search(it["paraphrase"])
        pos_hits.append({"id": it["id"], "text": it["text"], "paraphrase": it["paraphrase"],
                         "matched_id": matched_id, "score": round(score, 4)})

    print("检索域外问题（负例）...")
    neg_hits = []
    for q in OUT_OF_DOMAIN_QUERIES:
        _, matched_id, score, _ = dedup.search(q)
        neg_hits.append({"query": q, "matched_id": matched_id, "score": round(score, 4)})

    print("检索困难负例（关键词相近但问题不同）...")
    hard_hits = []
    for q in HARD_NEGATIVE_QUERIES:
        _, matched_id, score, _ = dedup.search(q)
        hard_hits.append({"query": q, "matched_id": matched_id, "score": round(score, 4)})

    dedup.threshold = original_threshold

    pos_vals = [h["score"] for h in pos_hits]
    neg_vals = [h["score"] for h in neg_hits]
    hard_vals = [h["score"] for h in hard_hits]

    sweep = []
    for th in THRESHOLDS:
        hit = sum(1 for h in pos_hits if h["score"] >= th and h["matched_id"] == h["id"])
        wrong = sum(1 for h in pos_hits if h["score"] >= th and h["matched_id"] != h["id"])
        miss = len(pos_hits) - hit - wrong
        fp = sum(1 for h in neg_hits if h["score"] >= th)
        tn = len(neg_hits) - fp
        fp_hard = sum(1 for h in hard_hits if h["score"] >= th)
        tn_hard = len(hard_hits) - fp_hard
        total = len(pos_hits)
        # 精确率按"命中 / (命中 + 误配 + 全部误报)"计算，误报含域外与困难负例两类
        fp_all = fp + fp_hard
        sweep.append({
            "threshold": th,
            "hit": hit, "miss": miss, "wrong": wrong,
            "fp": fp, "tn": tn, "fp_hard": fp_hard, "tn_hard": tn_hard,
            "hit_rate": round(hit / total, 4) if total else 0.0,
            "miss_rate": round(miss / total, 4) if total else 0.0,
            "wrong_rate": round(wrong / total, 4) if total else 0.0,
            "fp_rate": round(fp / len(neg_hits), 4) if neg_hits else 0.0,
            "fp_hard_rate": round(fp_hard / len(hard_hits), 4) if hard_hits else 0.0,
            "precision": round(hit / (hit + wrong + fp_all), 4) if (hit + wrong + fp_all) else 0.0,
            "recall": round(hit / total, 4) if total else 0.0,
        })
        row = sweep[-1]
        row["f1"] = round(2 * row["precision"] * row["recall"] / (row["precision"] + row["recall"]), 4) \
            if (row["precision"] + row["recall"]) else 0.0

    cfg = settings.DEDUP_THRESHOLD
    misses = [h for h in pos_hits if h["score"] < cfg]
    wrongs = [h for h in pos_hits if h["score"] >= cfg and h["matched_id"] != h["id"]]
    fps = [h for h in neg_hits if h["score"] >= cfg]
    hard_fps = [h for h in hard_hits if h["score"] >= cfg]

    result = {
        "mode": "dedup",
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "dataset_size": len(items),
        "indexed_count": len(indexed),
        "skipped_by_min_length": [{"id": it["id"], "text": it["text"], "length": len(it["text"])}
                                  for it in too_short],
        "query_skipped_by_min_length": [{"id": it["id"], "paraphrase": it["paraphrase"],
                                        "length": len(it["paraphrase"].strip())}
                                       for it in query_too_short],
        "min_text_length": min_len,
        "positive_queries": len(pos_hits),
        "negative_queries": len(neg_hits),
        "hard_negative_queries": len(hard_hits),
        "embedding_model": settings.EMBEDDING_MODEL,
        "configured_threshold": cfg,
        "score_stats": {
            "positive_mean": round(statistics.mean(pos_vals), 4) if pos_vals else None,
            "positive_median": round(statistics.median(pos_vals), 4) if pos_vals else None,
            "positive_min": round(min(pos_vals), 4) if pos_vals else None,
            "negative_mean": round(statistics.mean(neg_vals), 4) if neg_vals else None,
            "negative_max": round(max(neg_vals), 4) if neg_vals else None,
            "hard_negative_mean": round(statistics.mean(hard_vals), 4) if hard_vals else None,
            "hard_negative_max": round(max(hard_vals), 4) if hard_vals else None,
        },
        "sweep": sweep,
        "at_configured_threshold": {
            "misses": misses, "wrong_matches": wrongs,
            "false_positives": fps, "hard_false_positives": hard_fps,
        },
        "positive_details": sorted(pos_hits, key=lambda h: h["score"]),
        "negative_details": sorted(neg_hits, key=lambda h: h["score"], reverse=True),
        "hard_negative_details": sorted(hard_hits, key=lambda h: h["score"], reverse=True),
    }

    for p in tmp_dir.iterdir():
        p.unlink()
    tmp_dir.rmdir()
    return result


# ----------------------------------------------------------------------
# 联网：判定准确性评测
# ----------------------------------------------------------------------

def pick_subset(items, n):
    """按标签分层抽样，尽量均摊三类"""
    buckets = {"FALSE": [], "TRUE": [], "UNVERIFIED": []}
    for it in items:
        buckets.setdefault(it["label"], []).append(it)
    picked, idx = [], 0
    order = ["FALSE", "TRUE", "UNVERIFIED"]
    while len(picked) < n:
        added = False
        for label in order:
            bucket = buckets.get(label) or []
            if idx < len(bucket) and len(picked) < n:
                picked.append(bucket[idx])
                added = True
        if not added:
            break
        idx += 1
    return picked


def verdict_matches(label, verdict, can_judge):
    """判定正确性：UNVERIFIED 标签下"拒答"也算正确（正确弃权）"""
    if label == "UNVERIFIED":
        return (not can_judge) or verdict == "UNVERIFIED"
    if not can_judge:
        return False
    return verdict == label


def run_live(items, subset_size):
    sample = pick_subset(items, subset_size)
    search_tool = get_search_tool(settings.SEARCH_TOOL, settings.SEARCH_API_KEY)
    llm = OpenAICompatibleLLM(
        base_url=settings.LLM_BASE_URL, api_key=settings.LLM_API_KEY,
        model=settings.LLM_MODEL, timeout=settings.LLM_TIMEOUT,
        temperature=settings.LLM_TEMPERATURE, retry=settings.LLM_RETRY,
    )
    service = DetectService(search_tool=search_tool, llm=llm)

    print(f"搜索源={type(search_tool).__name__}  LLM={settings.LLM_MODEL}  样本数={len(sample)}")
    rows = []
    for i, it in enumerate(sample, 1):
        t0 = time.time()
        try:
            res = service.detect(it["text"])
            elapsed = time.time() - t0
            row = {
                "id": it["id"], "label": it["label"], "category": it["category"],
                "text": it["text"],
                "can_judge": res.can_judge, "verdict": res.verdict,
                "confidence": res.confidence, "error": res.error,
                "evidence_count": len(res.evidence or []),
                "elapsed_s": round(elapsed, 1),
                "correct": verdict_matches(it["label"], res.verdict, res.can_judge),
                "human_reason": res.human_reason,
            }
            rows.append(row)
            mark = "OK " if row["correct"] else "MISS"
            print(f"  [{i}/{len(sample)}] {mark} 标注={it['label']:10s} "
                  f"判定={res.verdict or '拒答'} error={res.error} "
                  f"证据={row['evidence_count']} {elapsed:.1f}s  #{it['id']}")
        except Exception as exc:
            elapsed = time.time() - t0
            rows.append({
                "id": it["id"], "label": it["label"], "category": it["category"],
                "text": it["text"], "can_judge": False, "verdict": None,
                "confidence": None, "error": True, "evidence_count": 0,
                "elapsed_s": round(elapsed, 1), "correct": False,
                "human_reason": f"调用异常：{type(exc).__name__}: {exc}",
            })
            print(f"  [{i}/{len(sample)}] EXC 标注={it['label']:10s} {exc}")

    graded = [r for r in rows if not r["error"]]
    latencies = [r["elapsed_s"] for r in rows]
    by_label = {}
    for label in ("FALSE", "TRUE", "UNVERIFIED"):
        group = [r for r in rows if r["label"] == label]
        if group:
            by_label[label] = {
                "count": len(group),
                "correct": sum(1 for r in group if r["correct"]),
                "accuracy": round(sum(1 for r in group if r["correct"]) / len(group), 4),
            }

    return {
        "mode": "live",
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "search_tool": type(search_tool).__name__,
        "llm_model": settings.LLM_MODEL,
        "sample_size": len(rows),
        "accuracy": round(sum(1 for r in rows if r["correct"]) / len(rows), 4) if rows else None,
        "error_count": sum(1 for r in rows if r["error"]),
        "abstain_count": sum(1 for r in graded if not r["can_judge"]),
        "mean_latency_s": round(statistics.mean(latencies), 1) if latencies else None,
        "max_latency_s": max(latencies) if latencies else None,
        "mean_evidence": round(statistics.mean([r["evidence_count"] for r in graded]), 1) if graded else None,
        "budget_s": settings.DETECT_TOTAL_BUDGET,
        "by_label": by_label,
        "rows": rows,
    }


# ----------------------------------------------------------------------
# SVG 图表（手写，无第三方依赖）
# ----------------------------------------------------------------------

def render_svg(dedup):
    w, h = 740, 400
    left, right, top, bottom = 70, 30, 50, 60
    plot_w, plot_h = w - left - right, h - top - bottom
    sweep = dedup["sweep"]
    xs = [s["threshold"] for s in sweep]
    x_min, x_max = min(xs), max(xs)

    def px(th):
        return left + (th - x_min) / (x_max - x_min) * plot_w

    def py(rate):
        return top + (1 - min(1.0, max(0.0, rate))) * plot_h

    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" '
        f'viewBox="0 0 {w} {h}" font-family="Segoe UI, PingFang SC, Microsoft YaHei, sans-serif">',
        f'<rect width="{w}" height="{h}" fill="#ffffff"/>',
        f'<text x="{left}" y="26" font-size="15" font-weight="600" fill="#222">'
        f'语义去重阈值敏感性</text>',
        f'<text x="{left}" y="43" font-size="11" fill="#777">'
        f'{dedup["positive_queries"]} 条同义改写查询（正例） / {dedup["negative_queries"]} 条域外查询（负例）'
        f'，索引 {dedup["indexed_count"]} 条</text>',
    ]
    for i in range(0, 6):
        rate = i / 5
        y = py(rate)
        parts.append(f'<line x1="{left}" y1="{y:.1f}" x2="{left + plot_w}" y2="{y:.1f}" '
                     f'stroke="#ececec" stroke-width="1"/>')
        parts.append(f'<text x="{left - 10}" y="{y + 4:.1f}" font-size="11" fill="#888" '
                     f'text-anchor="end">{rate * 100:.0f}%</text>')
    for th in xs:
        x = px(th)
        parts.append(f'<line x1="{x:.1f}" y1="{top + plot_h}" x2="{x:.1f}" y2="{top + plot_h + 4}" '
                     f'stroke="#999" stroke-width="1"/>')
        parts.append(f'<text x="{x:.1f}" y="{top + plot_h + 20}" font-size="11" fill="#666" '
                     f'text-anchor="middle">{th:.2f}</text>')
    parts.append(f'<text x="{left + plot_w / 2:.1f}" y="{h - 10}" font-size="12" fill="#444" '
                 f'text-anchor="middle">语义相似度阈值</text>')

    cfg = dedup["configured_threshold"]
    if x_min <= cfg <= x_max:
        cx = px(cfg)
        parts.append(f'<line x1="{cx:.1f}" y1="{top}" x2="{cx:.1f}" y2="{top + plot_h}" '
                     f'stroke="#f0a020" stroke-width="1.5" stroke-dasharray="5,4"/>')
        parts.append(f'<text x="{cx + 4:.1f}" y="{top + 12}" font-size="11" fill="#b07000">'
                     f'当前配置 {cfg}</text>')

    def draw(key, color):
        pts = " ".join(f"{px(s['threshold']):.1f},{py(s[key]):.1f}" for s in sweep)
        out = [f'<polyline points="{pts}" fill="none" stroke="{color}" stroke-width="2.5"/>']
        for s in sweep:
            out.append(f'<circle cx="{px(s["threshold"]):.1f}" cy="{py(s[key]):.1f}" r="3.5" '
                       f'fill="#fff" stroke="{color}" stroke-width="2"/>')
        return "".join(out)

    parts.append(draw("hit_rate", "#2f7ed8"))
    parts.append(draw("wrong_rate", "#d9534f"))
    parts.append(draw("fp_rate", "#8e6bd0"))

    lx, ly = left + plot_w - 190, top + 4
    parts.append(f'<rect x="{lx - 8}" y="{ly - 12}" width="198" height="66" fill="#fafafa" '
                 f'stroke="#e0e0e0" rx="4"/>')
    for i, (label, color) in enumerate([("命中自己（越高越好）", "#2f7ed8"),
                                        ("误配到别的记录", "#d9534f"),
                                        ("域外查询误报", "#8e6bd0")]):
        y = ly + i * 20
        parts.append(f'<line x1="{lx}" y1="{y}" x2="{lx + 22}" y2="{y}" stroke="{color}" stroke-width="2.5"/>')
        parts.append(f'<text x="{lx + 28}" y="{y + 4}" font-size="11" fill="#333">{label}</text>')

    parts.append('</svg>')
    return "\n".join(parts)


# ----------------------------------------------------------------------
# 报告
# ----------------------------------------------------------------------

def write_report(dedup=None, live=None):
    DOCS_DIR.mkdir(parents=True, exist_ok=True)
    lines = [
        "# AI 能力评测报告",
        "",
        f"> 生成时间：{datetime.now().strftime('%Y-%m-%d %H:%M')}",
        "> 由 `fact-agent/eval/evaluate.py` 自动生成；原始数据见 `fact-agent/eval/results/`",
        "",
        "## 重要局限（先读这一段）",
        "",
        "- 评测集 `fact-agent/eval/dataset.jsonl` 是**作者自行标注的 60 条内部小样本**，",
        "  **不是**公开基准数据集；标签代表作者判断，可用于回归对比与调参，不足以支撑对外的性能宣称。",
        "- 样本量小（60 条），指标存在较大随机波动，不能当作稳定性能指标。",
        "- 去重评测只用本地 embedding 模型（bge-small-zh），不需要联网，可完全复现；",
        "  判定评测需要真实 LLM 与搜索服务，会消耗 API 配额，且结论受搜索源质量影响。",
        "- 评测方法：去重评测用**同义改写**作为留出查询（期望命中自己的原文），",
        "  另用一组**域外问题**作为负例（期望不命中）。不能拿“已入库的原文”当查询——那样必然命中自己。",
        "",
    ]

    if dedup:
        s = dedup["score_stats"]
        cfg = dedup["configured_threshold"]
        at = dedup["at_configured_threshold"]
        lines += [
            "## 一、语义去重评测（离线）",
            "",
            f"- 数据集：**{dedup['dataset_size']}** 条，其中进入索引 **{dedup['indexed_count']}** 条",
            f"- 正例查询：**{dedup['positive_queries']}** 条同义改写；"
            f"负例查询：**{dedup['negative_queries']}** 条域外问题 + "
            f"**{dedup['hard_negative_queries']}** 条困难负例（关键词相近但问题不同）",
            f"- embedding 模型：`{pathlib.Path(dedup['embedding_model']).name}`",
            f"- 当前配置阈值：**{cfg}**，最短文本长度：{dedup['min_text_length']}",
            "",
        ]
        if dedup["skipped_by_min_length"]:
            lines += [
                f"⚠️ 有 **{len(dedup['skipped_by_min_length'])}** 条样本因长度不足 "
                f"`min_text_length={dedup['min_text_length']}` **未进入索引**，"
                "因此这些消息只能走精确去重、无法被语义命中：",
                "",
                "| ID | 原文 | 字数 |",
                "| --- | --- | --- |",
            ]
            for it in dedup["skipped_by_min_length"]:
                lines.append(f"| {it['id']} | {it['text']} | {it['length']} |")
            lines.append("")
        if dedup.get("query_skipped_by_min_length"):
            lines += [
                f"⚠️ 另有 **{len(dedup['query_skipped_by_min_length'])}** 条**改写句本身**短于 "
                f"`min_text_length={dedup['min_text_length']}`，检索会被直接短路（得 0 分），"
                "已从指标分母中剔除并单独列出：",
                "",
                "| ID | 改写句 | 字数 |",
                "| --- | --- | --- |",
            ]
            for it in dedup["query_skipped_by_min_length"]:
                lines.append(f"| {it['id']} | {it['paraphrase']} | {it['length']} |")
            lines.append("")

        lines += [
            "### 相似度分布",
            "",
            "| 指标 | 同义改写（正例） | 域外问题（易负例） | 关键词相近但问题不同（困难负例） |",
            "| --- | --- | --- | --- |",
            f"| 均值 | {s['positive_mean']} | {s['negative_mean']} | {s.get('hard_negative_mean')} |",
            f"| 中位数 | {s['positive_median']} | — | — |",
            f"| 最小值 / 最大值 | {s['positive_min']} | {s['negative_max']} | {s.get('hard_negative_max')} |",
            "",
            "说明：**正例最小值** 与 **两类负例的最大值** 之间就是可用的阈值区间；",
            "困难负例的存在会把该区间上限压低，因此它才是决定阈值能不能下调的关键。",
            "",
            "### 阈值敏感性",
            "",
            "| 阈值 | 命中自己 | 漏判 | 误配到别的记录 | 域外误报 | 困难负例误报 | 命中率 | 误报率(困难) | 精确率 | 召回 | F1 |",
            "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |",
        ]
        for row in dedup["sweep"]:
            star = " ⭐" if abs(row["threshold"] - cfg) < 1e-9 else ""
            lines.append(
                f"| {row['threshold']:.2f}{star} | {row['hit']} | {row['miss']} | {row['wrong']} | {row['fp']} | "
                f"{row['fp_hard']} | {row['hit_rate'] * 100:.1f}% | {row['fp_hard_rate'] * 100:.1f}% | "
                f"{row['precision'] * 100:.1f}% | {row['recall'] * 100:.1f}% | {row['f1']:.3f} |"
            )
        lines += ["", "![阈值敏感性](评测-阈值敏感性.svg)", ""]

        lines += ["### 当前阈值下的漏判（同义改写未被识别为同一问题）", ""]
        if at["misses"]:
            lines += ["| ID | 原消息 | 同义改写 | 相似度 |", "| --- | --- | --- | --- |"]
            for m in at["misses"]:
                lines.append(f"| {m['id']} | {m['text']} | {m['paraphrase']} | {m['score']} |")
        else:
            lines.append("无：当前阈值下所有同义改写都被正确命中。")
        lines += ["", "### 当前阈值下的误配（改写句命中了别的记录，比漏判更危险）", ""]
        if at["wrong_matches"]:
            lines += ["| ID | 同义改写 | 命中的记录 ID | 相似度 |", "| --- | --- | --- | --- |"]
            for m in at["wrong_matches"]:
                lines.append(f"| {m['id']} | {m['paraphrase']} | {m['matched_id']} | {m['score']} |")
        else:
            lines.append("无。")
        lines += ["", "### 域外查询中相似度最高的几条（易负例误报风险）", "",
                  "| 查询 | 命中的记录 ID | 相似度 |", "| --- | --- | --- |"]
        for m in dedup["negative_details"][:6]:
            lines.append(f"| {m['query']} | {m['matched_id']} | {m['score']} |")
        lines += ["", "### 困难负例的相似度（关键词相近但问题不同，**必须低于阈值**）", "",
                  "| 查询 | 命中的记录 ID | 相似度 | 是否误报（按当前阈值） |", "| --- | --- | --- | --- |"]
        cfg_val = dedup["configured_threshold"]
        for m in dedup["hard_negative_details"]:
            flag = "❌ 误报" if m["score"] >= cfg_val else "✅"
            lines.append(f"| {m['query']} | {m['matched_id']} | {m['score']} | {flag} |")
        lines.append("")

    if live:
        lines += [
            "## 二、事实判定评测（联网）",
            "",
            f"- 搜索源：`{live['search_tool']}`，LLM：`{live['llm_model']}`",
            f"- 样本数：**{live['sample_size']}**（按标签分层抽样）",
            f"- **准确率：{live['accuracy'] * 100:.1f}%**",
            f"- 服务失败（error=true）：{live['error_count']} 条；拒答（can_judge=false）：{live['abstain_count']} 条",
            f"- 平均耗时：**{live['mean_latency_s']}s**（最大 {live['max_latency_s']}s，预算 {live['budget_s']}s）",
            f"- 平均证据数：{live['mean_evidence']} 条",
            "",
            "> 标签为 `UNVERIFIED` 的样本，模型回答“无法判定/拒答”也计为正确（正确弃权）。",
            "",
        ]
        if live.get("by_label"):
            lines += ["| 标签 | 样本数 | 判定正确 | 准确率 |", "| --- | --- | --- | --- |"]
            for label, stat in live["by_label"].items():
                lines.append(f"| {label} | {stat['count']} | {stat['correct']} | {stat['accuracy'] * 100:.1f}% |")
            lines.append("")
        lines += [
            "| ID | 标注 | 判定 | 置信度 | 证据 | 耗时(s) | 结果 |",
            "| --- | --- | --- | --- | --- | --- | --- |",
        ]
        for r in live["rows"]:
            verdict = r["verdict"] or ("拒答" if not r["error"] else "失败")
            conf = f"{r['confidence']:.2f}" if r["confidence"] is not None else "—"
            lines.append(
                f"| {r['id']} | {r['label']} | {verdict} | {conf} | {r['evidence_count']} | "
                f"{r['elapsed_s']} | {'✅' if r['correct'] else '❌'} |"
            )
        lines.append("")

    lines += [
        "## 三、如何复现",
        "",
        "```bash",
        "# 离线去重评测（不需要任何 Key）",
        "py -3.10 fact-agent/eval/evaluate.py --mode dedup",
        "",
        "# 联网判定评测（需要 LLM_API_KEY 与 SEARCH_TOOL/SEARCH_API_KEY）",
        "py -3.10 fact-agent/eval/evaluate.py --mode live --subset 10",
        "",
        "# 两者都跑并刷新本报告",
        "py -3.10 fact-agent/eval/evaluate.py --mode all --subset 10",
        "```",
        "",
        "## 四、结论与调参建议",
        "",
    ]
    if dedup:
        cfg = dedup["configured_threshold"]
        row = next((r for r in dedup["sweep"] if abs(r["threshold"] - cfg) < 1e-9), None)
        if row:
            lines.append(
                f"- 当前阈值 {cfg} 下：命中率 **{row['hit_rate'] * 100:.1f}%**、"
                f"误配率 {row['wrong_rate'] * 100:.1f}%、域外误报率 {row['fp_rate'] * 100:.1f}%、"
                f"F1 {row['f1']:.3f}。"
            )
        # 数据驱动的推荐：先要求"误配、易负例误报、困难负例误报都为 0"，再在其中取命中率最高的一档
        clean = [r for r in dedup["sweep"]
                 if r["fp_rate"] == 0 and r["fp_hard_rate"] == 0 and r["wrong_rate"] == 0]
        aggressive = max(clean, key=lambda r: r["hit_rate"]) if clean else None
        ceilings = [x for x in (s.get("negative_max"), s.get("hard_negative_max")) if x is not None]
        neg_ceiling = max(ceilings) if ceilings else None
        if neg_ceiling is not None:
            lines.append(
                f"- 相似度分离：正例最小值 **{s['positive_min']}**，两类负例最大值 **{neg_ceiling}**；"
                f"本次样本上可用的阈值区间约为 **({neg_ceiling}, {s['positive_min']}]**。"
            )
        if aggressive:
            lines.append(
                f"- 在「误配率、易负例误报率、困难负例误报率同时为 0」的前提下，"
                f"命中率最高的阈值是 **{aggressive['threshold']:.2f}**"
                f"（命中率 {aggressive['hit_rate'] * 100:.1f}%）。"
            )
        if row and aggressive and aggressive["hit_rate"] > row["hit_rate"] + 0.1:
            lines.append(
                f"- ⚠️ **当前配置的阈值 {cfg} 明显高于正例分布的集中区间**，"
                f"导致语义去重实际上几乎不触发（命中率仅 {row['hit_rate'] * 100:.1f}%）；"
                f"建议下调到 {aggressive['threshold']:.2f} 附近，并在《技术方案》中同步说明取舍。"
            )
        lines.append(
            "- **不要指望找到「完美阈值」**：本次样本中正例最小值与困难负例最大值是重叠的，"
            "任何单一阈值都必然在「漏判」与「误配」之间取舍。"
        )
        lines.append(
            "- 更稳妥的产品设计是**分级处理**而不是单一阈值："
            "高相似（如 ≥0.85）直接复用历史结论；中等相似（如 0.65~0.85）提示"
            "「发现相似问题」但仍照常检测（或让用户确认）；低相似当作新问题。"
            "这样既提高复用率，又避免把不同问题当成同一个而直接给出错误结论。"
        )
        lines.append(
            "- 需要说明：本评测的「困难负例」中有一部分其实是历史条目的**子问题**"
            "（例如「喝醋能软化血管吗」与数据集里那条「喝醋能软化血管…代替降压药」），"
            "这类命中未必算真误报，因此上表的困难负例误报数是**偏保守的上界**。"
        )
        best = max(dedup["sweep"], key=lambda r: r["f1"])
        lines.append(
            f"- 本次样本上 F1 最高的阈值是 **{best['threshold']:.2f}**（F1={best['f1']:.3f}）；"
            "样本量小，只能作为初步参考，扩样后再定。"
        )
        lines.append(
            "- 权衡原则：阈值偏高会漏判（用户拿不到可复用的结论、重复调用 LLM 更贵）；"
            "偏低会**误配**（把不同问题当成同一个，直接给出错误结论，风险更大）。"
            "因此在两者不可兼得时，应优先压低误配率。"
        )
        lines.append(
            f"- 注意 `min_text_length={dedup['min_text_length']}` 会把短消息挡在语义去重之外，"
            "若业务中短消息（如“XX 是真的吗”）占比高，应下调该阈值或改为按字符数与语义双条件判断。"
        )
    lines += [
        "- 若要提升去重质量，优先做两件事：① 扩充评测样本到数百条并覆盖真实业务分布；",
        "  ② 给 bge 加上官方建议的 query 指令前缀，并尝试更大的中文向量模型做对比。",
        "",
    ]

    out = DOCS_DIR / "评测报告.md"
    out.write_text("\n".join(lines), encoding="utf-8")
    print(f"已写入报告：{out}")
    return out


def save_raw(name, data):
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    path = RESULTS_DIR / f"{name}.json"
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"已写入原始数据：{path}")


def main():
    parser = argparse.ArgumentParser(description="FactCheck AI 能力评测")
    parser.add_argument("--mode", choices=["dedup", "live", "all"], default="dedup")
    parser.add_argument("--subset", type=int, default=10, help="联网评测的样本数（分层抽样）")
    args = parser.parse_args()

    items = load_dataset()
    print(f"数据集：{len(items)} 条（{DATASET}）")

    dedup_result = live_result = None
    if args.mode in ("dedup", "all"):
        dedup_result = run_dedup(items)
        save_raw("dedup", dedup_result)
        (DOCS_DIR / "评测-阈值敏感性.svg").write_text(render_svg(dedup_result), encoding="utf-8")
        print(f"已写入图表：{DOCS_DIR / '评测-阈值敏感性.svg'}")
    if args.mode in ("live", "all"):
        live_result = run_live(items, args.subset)
        save_raw("live", live_result)

    if dedup_result or live_result:
        write_report(dedup_result, live_result)


if __name__ == "__main__":
    main()
