# FactCheck Python Agent

事实检测项目的 Python Agent 服务，提供语义去重（bge + FAISS）与联网 LLM 事实检测能力。

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /health | 健康检查（回显搜索源、是否联网、模型与 Key 配置） |
| POST | /dedup | 语义去重：`{text}` → `{matched, record_id, score, matched_text}` |
| POST | /index/add | 新增向量索引：`{record_id, text}` |
| POST | /detect | LLM 事实检测：`{text}` → `{can_judge, verdict, confidence, reasoning_chain, evidence[], human_reason, error}` |

`error=true` 表示服务侧故障（LLM 调用失败 / 输出无法解析 / 超出时间预算），
Java 侧应据此记为**检测失败**（可重试），而不是把消息转入人工诊断队列。

## 安装与启动（推荐 Python 3.10）

```bash
cd fact-agent
python -m venv .venv
# Windows: .venv\Scripts\activate    Linux/macOS: source .venv/bin/activate

python -m pip install -r requirements.txt

cp .env.example .env
# 编辑 .env：填入 LLM_API_KEY；要联网核查请设置 SEARCH_TOOL=tavily|bocha|serpapi 与 SEARCH_API_KEY

python -m uvicorn app.main:app --host 0.0.0.0 --port 8001

# 等价方式：直接以模块方式启动（监听地址与端口取自 .env 的 AGENT_HOST / AGENT_PORT）
python -m app.main
```

> 判断是否真的起来了：日志里必须出现 **`Uvicorn running on http://...`**。
> 若只看到"搜索源已启用 / 检测时间预算"这几行然后就回到命令提示符，
> 说明只是执行了模块级初始化而没有启动服务（本文件已补上 `__main__` 入口，正常不会再出现）。
>
> 自检一行即可确认配置是否生效：`/health` 返回的 `search_offline`（是否不联网）与 `llm_configured`。

> 🩺 **启动器报 `Fatal error in launcher: Unable to create process` 怎么办？**
> 这几乎总是因为**项目文件夹被改名或移动过**：Windows 的 venv 不可迁移，
> `Scripts\*.exe`（`uvicorn.exe`/`pip.exe` 等）里内嵌着创建时的解释器绝对路径，
> 目录一变就全部失效（`Scripts\activate.bat` 里的 `VIRTUAL_ENV` 同样是旧路径）。
>
> - 立刻可用：所有命令改用 `python -m <模块>` 形式（`python -m pip`、`python -m uvicorn`、`python -m app.main`），
>   它们不经过这些转发器；`Scripts\python.exe` 本身仍是好的（它靠旁边的 `pyvenv.cfg` 定位）。
> - 彻底修复：删掉 `.venv`，在**当前**目录重新 `python -m venv .venv`，再 `python -m pip install -r requirements.txt`。

> ⚠️ **必须以单 worker 运行，不要加 `--workers`**：语义去重索引是进程内内存态，
> 多 worker 会各持一份索引并互相覆盖。需要横向扩展时应改为外部向量库（Qdrant/Milvus）。

## 环境变量

完整清单见 `.env.example`，主要的：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `AGENT_HOST` / `AGENT_PORT` | `127.0.0.1` / `8001` | **默认只监听回环**：Agent 只需被同机 Java 调用。跨机部署才改 `0.0.0.0`，且必须同时更换 `INTERNAL_TOKEN` |
| `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL` | — | OpenAI 兼容 LLM |
| `LLM_TIMEOUT` / `LLM_RETRY` | `60` / `2` | 单次请求超时（秒）与重试次数 |
| `LLM_TEMPERATURE` | `0.1` | 事实核查需要稳定 JSON 输出，不建议调高 |
| `SEARCH_TOOL` | `dummy` | `dummy`（不联网）/ `tavily` / `serpapi` / `bocha` |
| `SEARCH_TOP_K` / `SEARCH_FETCH_TOP` | `10` / `3` | 检索条数 / 抓取正文条数；抓取设为 0 可关闭 |
| `SEARCH_TIMEOUT` / `SEARCH_PAGE_TIMEOUT` | `20` / `10` | 单次搜索 / 单次抓取超时（秒） |
| `SEARCH_PAGE_MAX_CHARS` | `1500` | 单条证据保留的网页正文字符数 |
| `DETECT_TOTAL_BUDGET` | `45` | 单次检测整体时间预算（秒），**必须小于** Java 侧 `fact-check.agent.timeout`（默认 90） |
| `DETECT_MAX_TEXT_LENGTH` | `5000` | 待检测文本上限，超出截断 |
| `DEDUP_THRESHOLD` | `0.92` | 语义去重阈值（S10 可配） |
| `DEDUP_MIN_TEXT_LENGTH` | `8` | 低于该长度只做精确去重 |
| `EMBEDDING_MODEL` | `BAAI/bge-small-zh-v1.5` | 支持 HuggingFace 名称或本地目录；**相对路径相对 `fact-agent/` 解析** |
| `AGENT_DATA_DIR` | `./data` | FAISS 索引与元数据目录（相对 `fact-agent/` 解析） |
| `INTERNAL_TOKEN` | `dev-internal-token-change-me` | 内部鉴权。**必须与 Java 侧 `fact-check.agent.internal-token` 一致**；留空则内部接口一律 401（fail-closed） |
| `CREDIBILITY_RULES_FILE` | 空 | 来源可信度规则库路径；留空用 `fact-agent/config/credibility_rules.json` |

## Java 调用约定

Java 通过 HTTP 调用，默认地址 `http://127.0.0.1:8001`。
`/detect`、`/dedup`、`/index/add` 都需要请求头 `X-Internal-Token`（Java 侧 `AgentClient` 会自动附加）：
未配置 `INTERNAL_TOKEN` 时这些接口一律返回 401，`/health` 不受影响（便于探活）。

## 实现说明

- **语义去重**：`IndexFlatIP` + 向量归一化（等价余弦相似度），索引与元数据持久化在 `data/`。
  写盘采用"临时文件 + 原子替换"，并用锁保护并发；加载失败时把可疑文件另存为 `.bak`，
  **不会**用空索引覆盖原文件。
- **联网检测**：LLM 生成 1-3 个查询词 → 多查询检索并按 URL 去重 → 抓取前 N 条网页正文
  （正文存 `content`，不覆盖原始摘要）→ 组装提示词 → 强制校验 JSON 输出。
- **来源可信度（S13）**：确定性域名规则表，规则**外置**在 `config/credibility_rules.json`
  （`exact` 精确匹配 / `suffixes` 后缀匹配，按档位顺序命中），改词表不用改代码。
  **不采信模型自报的可信度**（自报数字无法复核）；未收录域名落到 `unknown_score`（默认 0.3，
  语义是"未收录"而不是"不可信"）。文件缺失或写坏会回退内置兜底规则，不会让 Agent 起不来。
- **相关度**：**只用一种确定性口径**——`位置分 × (0.3 + 0.7 × 词面重合率)`（字符二元组）。
  乘性组合保证"排得靠前"不会单独换来中高分（完全不重合时最多 0.3）。
  模型自报的 `relevance_score` **不参与排序、也不写进结果**（只记 debug 日志），
  理由与来源可信度相同：自报数字无法复核，且与本地口径不同量纲，混排会产生不可解释的顺序。
- **时间预算**：各步骤按整体预算的剩余时间设置超时，预算耗尽提前收尾并转人工。
- **防提示词注入**：用户文本与网页正文属于不可信输入，统一放入 `<untrusted_*>` 标签、
  转义边界字面量，系统提示声明其仅为数据，输出侧再做字段强制校验。
- **证据合并**：模型给出的证据优先，但**不再整体丢弃检索结果**（否则检索到的权威来源会因模型未提及而消失）：
  按 URL 去重合并、丢弃字段全空的条目、上限 10 条，最终按相关度降序。
- 首次调用 `/dedup` 或 `/index/add` 时会加载 Embedding 模型，耗时较长。

## 测试

```bash
cd fact-agent
python -m unittest discover -s tests -t . -v     # 63 个用例
```

测试**完全离线**（标准库 `unittest`，零第三方依赖），并有三道防线保证不会误连网络：
`requests` 全部打桩、`socket.connect` 熔断（任何对外 TCP 立即失败）、`SEARCH_FETCH_TOP` 临时置 0。
另有反向用例专门证明"不关抓取就真的会出网"，避免防线形同虚设。
索引相关用例一律使用 `tempfile` 临时目录，不会写坏 `data/` 里的真实索引。

## 能力评测

```bash
py -3.10 eval/evaluate.py --mode dedup              # 离线去重评测（无需任何 Key）
py -3.10 eval/evaluate.py --mode live --subset 10   # 联网判定评测（需要 LLM 与搜索源）
py -3.10 eval/evaluate.py --mode all --subset 10    # 两者都跑并刷新报告
```

评测集为 `eval/dataset.jsonl`（60 条作者标注样本，含同义改写；**非公开基准**）。
结果写入 `../docs/评测报告.md` 与 `eval/results/*.json`，图表为 `../docs/评测-阈值敏感性.svg`。

实测结论摘要：默认去重阈值 **0.92 偏高**（同义改写命中率仅 3.5%）；
联网判定在明确真假样本上 **7/7**，但在"无法核实"的传闻上倾向强行给结论（1/3）。
