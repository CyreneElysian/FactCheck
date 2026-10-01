# FactCheck · 事实检测平台

> “AI 检测 + 人工兜底”的事实核查平台：用户提交一段文字或一张图片，系统先做去重、再交给可联网检索的大模型判断，给出**真 / 假 / 无法判定**的结论与完整推理链；AI 判不了的进入人工诊断队列，由管理员处置，并支持申诉、权威信息发布与结论版本留痕。

覆盖 **Web 端（用户 + 管理）**、**微信小程序端**，后端由 **Java 业务服务 + Python AI Agent** 两个进程协作。

---

## 目录

- [核心功能](#核心功能)
- [技术栈](#技术栈)
- [系统架构](#系统架构)
- [目录结构](#目录结构)
- [快速开始](#快速开始)
- [端口与账号](#端口与账号)
- [配置说明](#配置说明)
- [接口约定](#接口约定)
- [项目状态与已知不足](#项目状态与已知不足)
- [文档导航](#文档导航)

---

## 核心功能

### 事实检测（核心链路）

1. 提交前内容安全审核 → 2. 内容规范化 + SHA-256 哈希 → 3. **精确去重**（命中直接复用历史结论）
→ 4. **语义去重**（bge-small-zh-v1.5 + FAISS 余弦相似度，阈值可配，默认 0.92）
→ 5. 权威信息匹配 → 6. 异步调用 LLM Agent（生成检索词 → 多查询搜索 → 抓取正文 → 强约束 JSON 输出）
→ 7. 能判定则落结论/置信度/推理链/证据（含来源可信度）；不能判定则转人工诊断队列。

前端通过 **WebSocket 实时推送 + 2 秒轮询兜底** 获取结果。

### 普通用户

注册登录、微信绑定登录、修改资料/密码/头像、注销账号；文字与图片（OCR）检测；历史记录（筛选 + 分页）；
记录详情（推理链、证据列表、来源可信度、权威信息卡片）；热度榜 Top10；权威信息浏览；申诉/纠错；
收藏、分享卡片（canvas 生成）、结果评分（有帮助/无帮助）；站内信（未读红点）。

### 管理员

工作台统计（今日检测量/AI 判定数/人工诊断数/待诊断数/待处理申诉数/注册用户数）；
待诊断 Top10（超时高亮、转派）；诊断工作台（判定结论 + 推理依据）；
结论管理（查询全部记录、修改历史结论并附原因、修订历史可追溯）；
申诉处理（采纳需给出新结论与新推理依据 / 驳回需填写原因）；权威信息管理（增删改 + 上下线）；
账号管理（创建管理员、启用/禁用用户）；操作日志。

### 微信小程序

微信一键登录 + 绑定 Web 账号（同一张 `t_user` 表，账号天然同步）；文字检测与 2 秒轮询；
图片选择 + OCR 识别；历史记录、热度榜、权威信息、我的（收藏/申诉/站内信/资料）。

---

## 技术栈

| 层 | 选型 |
| --- | --- |
| Web 前端 | Vue 3 + TypeScript + Element Plus + Pinia + Vue Router 4 + Axios + Vite 5 |
| 移动端 | 微信小程序（原生） |
| Java 后端 | Spring Boot 2.7.3（Java 11）、MyBatis（XML Mapper）、Druid、PageHelper、JWT（jjwt）、WebSocket |
| 数据库 | MySQL 8.0（12 张表，无物理外键，一致性由代码保证） |
| 缓存 | Redis 7（热度榜 ZSET + Lua 衰减、限流、序号计数） |
| AI 服务 | Python 3.10 + FastAPI + Uvicorn |
| 向量检索 | sentence-transformers（`BAAI/bge-small-zh-v1.5`）+ FAISS `IndexFlatIP`（归一化 = 余弦相似度，本地持久化） |
| LLM | OpenAI 兼容接口（可配 DeepSeek / Qwen / GPT / Kimi 等） |
| 搜索 | 可插拔 `SearchTool`：`dummy`（不联网）/ `tavily` / `serpapi` / `bocha` |
| 云服务 | 阿里云 OSS（图片存储）、阿里云 OCR（可替换接口）、阿里云内容安全（预留） |
| 部署 | Nginx 1.20.2（静态托管 + `/api` 反向代理 + WebSocket 升级） |

---

## 系统架构

```
        浏览器 (Vue3)                    微信小程序
             │                                │
             └───────────┬────────────────────┘
                         ▼
           Nginx 1.20.2 :80
             ├── /            静态资源 html/fact-check（SPA，try_files 回退 index.html）
             └── /api/   ──►  Spring Boot :8080（context-path = /api，含 /api/ws/detect WebSocket）
                                  │
                                  ├── MySQL 8.0 :3306     关系数据（12 张表）
                                  ├── Redis 7  :6379      热度榜 ZSET + 限流
                                  ├── 阿里云 OSS          图片/头像存储
                                  ├── 阿里云 OCR          图片文字识别（接口可替换）
                                  ├── 微信 code2session   小程序登录换取 openid/unionid
                                  └── Python Agent :8001  仅内网
                                          ├── /dedup       语义去重（bge + FAISS）
                                          ├── /detect      LLM + 搜索 事实检测
                                          ├── /index/add   增量写入向量索引
                                          └── /health      健康检查
                                                  │
                                                  ├── LLM API（公网）
                                                  └── Search API（公网）
```

**设计要点**

- **为什么拆两个进程**：Java 负责业务与事务性状态，AI 能力（本地 embedding 模型常驻、FAISS 索引、LLM 与检索编排）独立部署，通过 HTTP + JSON 契约通信；OCR / LLM / 搜索均通过接口抽象，便于替换实现。
- **热度榜**：Redis ZSET（key `fact:hot:claim`），5 类行为加权，每日 03:00 用 Lua 脚本做分数衰减并清理低分成员。
- **检测异步化**：`@Async("detectExecutor")` 独立线程池，避免 HTTP 长时间阻塞；结果通过 WebSocket 推送。
- **结论可追溯**：每次修改结论写 `t_conclusion_revision` 版本快照（含旧/新结论、旧/新推理链、原因、来源），记录 `version + 1`。

---

## 目录结构

```
fact-check/
├── docs/                     设计与过程文档
│   ├── 产品文档.md            需求、角色权限、页面清单、验收标准（14 条）
│   ├── 技术方案.md            架构、数据库、Redis、接口、Python Agent、部署
│   └── 项目改进清单.md        代码审计结果与按优先级排序的待办（60 项）
├── sql/
│   ├── fact_check_schema.sql  建库建表（12 张表，可重复执行，会清空数据）
│   └── fact_check_init.sql    初始化数据（创建第一个管理员，可重复执行）
├── fact-check/                Java 后端（Maven 多模块）
│   ├── pom.xml
│   ├── fact-check-common/     公共模块：Result/PageResult、常量、异常、工具、属性类
│   ├── fact-check-pojo/       实体 / DTO / VO
│   ├── fact-check-server/     控制器 / 服务 / Mapper / 配置 / 拦截器 / 定时任务
│   └── scripts/
│       └── generate_test_data.py  演示数据生成脚本（幂等）
├── fact-agent/                Python AI Agent（FastAPI）
│   ├── app/
│   │   ├── main.py            四个接口入口 + 内部鉴权
│   │   ├── config.py          集中配置（.env 加载）
│   │   ├── schemas.py         Pydantic 模型
│   │   ├── prompts/           提示词模板
│   │   └── services/          dedup / detect / embedding / llm / search / credibility
│   ├── data/                  FAISS 索引与元数据（运行时自动创建，不入库）
│   ├── models/                本地 embedding 模型（不入库，首次运行自动下载）
│   ├── .env.example           环境变量模板
│   └── requirements.txt
├── fact-check-web/            Vue3 前端
│   └── src/{api,layouts,router,store,utils,views}
├── fact-check-mini/           微信小程序
│   ├── pages/{login,index,history,detail,hot,authority,mine,messages,favorites,appeals,profile}
│   └── utils/{config,request,auth}
└── nginx-1.20.2/              Nginx 1.20.2（仓库仅保留 conf/ 与占位页面）
```

---

## 快速开始

### 环境要求

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 11+ | 项目编译目标为 Java 11 |
| Maven | 3.6+ | |
| MySQL | 8.0+ | |
| Redis | 7.x | |
| Python | 3.10（推荐） | Agent 服务；模型首次运行需联网下载 |
| Node.js | 18+ | Vite 5 要求 |
| Nginx | 1.20.2 | 仓库内已附带，仅保留了配置文件 |
| 微信开发者工具 | 最新 | 仅小程序端需要 |

### 1. 初始化数据库

```bash
mysql -u root -p < sql/fact_check_schema.sql   # 建库建表（会先 DROP，谨慎）
mysql -u root -p < sql/fact_check_init.sql     # 创建第一个管理员 admin / Admin@123456
```

### 2. 配置本地密钥（Java 侧）

```bash
cd fact-check/fact-check-server/src/main/resources
cp application-local.yml.example application-local.yml
# 编辑 application-local.yml，填入你的 MySQL 密码、OSS 密钥、微信 AppSecret
```

`application-local.yml` 已在 `.gitignore` 中，**不会被提交**。

> ⚠️ 它默认会被**本地构建**打进来（这是有意的，见下方说明）；构建"可分发产物"时请加 `-Pdist`：
>
> ```bash
> mvn clean package -Pdist -DskipTests   # 该 profile 会把 application-local.yml 排除在产物之外
> ```
>
> **为什么不默认排除**：IDEA 与 `mvn spring-boot:run` 运行时用的类路径是 `target/classes`，
> 一旦在默认构建里排除该文件，`target/classes` 里就不会有它，激活 `local` profile 后
> `${fact-check.datasource.*}` 会全部无法解析，启动直接失败：
> `java.sql.SQLException: ${fact-check.datasource.driver-class-name}`。

### 3. 启动 Python Agent

```bash
cd fact-agent
python -m venv .venv
# Windows: .venv\Scripts\activate    Linux/macOS: source .venv/bin/activate
pip install -r requirements.txt

cp .env.example .env
# 编辑 .env：至少填写 LLM_API_KEY；要联网核查请把 SEARCH_TOOL 从 dummy 改为 tavily/bocha/serpapi 并填 SEARCH_API_KEY

uvicorn app.main:app --host 0.0.0.0 --port 8001
```

> 首次调用 `/dedup` 或 `/index/add` 会下载并加载 embedding 模型，耗时较长。
>
> ⚠️ **不要加 `--workers`**：语义去重索引是进程内内存态，多 worker 会各持一份并互相覆盖。
>
> 启动日志会自检搜索源与 LLM 配置：若为 `dummy` 会明确告警"不联网"，缺 Key 会报错，便于第一时间发现"看起来能跑、实际没联网"。

### 4. 启动 Java 后端

```bash
cd fact-check
mvn package -DskipTests
java -jar fact-check-server/target/fact-check-server-1.0-SNAPSHOT.jar --spring.profiles.active=local
```

- **IDE 运行（推荐）**：直接运行 `FactCheckApplication`，把 **Active profiles 设为 `local`**。
  默认构建会把 `src/main/resources/application-local.yml` 复制到 `target/classes`，因此 IDEA 能直接读到。
  启动日志会打印一行 `启动环境：activeProfiles=[local], dbUrl=..., dbUsername=...`，
  如果 `dbUrl` 是 `null` 或仍带着 `${...}`，就说明配置文件没被加载。
- **用 jar 运行**：默认构建的 jar 里已含该文件，同样加 `--spring.profiles.active=local` 即可；
  若用的是 `-Pdist` 构建的 jar（不含本地配置），则改用环境变量注入，或用
  `--spring.config.additional-location=file:./fact-check/fact-check-server/src/main/resources/` 指定外部配置目录。

### 5. 构建并部署前端

```bash
cd fact-check-web
npm install
npm run dev            # 开发模式（Vite 已配置 /api 代理到 8080）

# 生产构建 + 部署到 nginx
npm run build
# 将 dist 内容复制到 nginx 静态目录
#   Windows: xcopy /E /Y dist\* ..\nginx-1.20.2\html\fact-check\
#   Linux/macOS: cp -r dist/* ../nginx-1.20.2/html/fact-check/
```

### 6. 启动 Nginx

```bash
cd nginx-1.20.2
# Windows: start nginx        Linux/macOS: ./nginx
```

访问 <http://localhost> 打开 Web 端。

### 7. 小程序（可选）

用微信开发者工具打开 `fact-check-mini` 目录。注意：

- 后端地址在 `fact-check-mini/utils/config.js` 中配置，默认 `http://127.0.0.1:8080/api`（仅开发者工具本地调试可用）。
- 真机调试/发布需要 HTTPS 域名，并在微信公众平台配置 request/uploadFile 合法域名。
- 微信登录需要 `application-local.yml` 中配置正确的 `appid` / `secret`。

### 8. 演示数据（可选）

```bash
py -3.10 -m pip install -r fact-check/scripts/requirements.txt

# 先预览将要执行的 SQL（不连数据库、不需要任何依赖）
py -3.10 fact-check/scripts/generate_test_data.py --dry-run

# 实际写入（幂等，可反复执行）
py -3.10 fact-check/scripts/generate_test_data.py
```

生成演示用户、权威信息、检测记录（含 1 条图片记录）、证据、站内信、收藏/评分/申诉、
检测请求日志、操作日志、结论修订、内容安全审核日志与 Redis 热度数据，并把有结论的记录
通过 `/index/add` 同步进 Agent 的向量索引（否则库里已有的记录永远命不中语义去重）。
Agent 未启动时加 `--skip-index` 跳过该步。

### 9. 运行测试

全部测试**离线可跑**（不需要 MySQL / Redis / 网络）：

```bash
# Java：62 个用例（JUnit 5 + Mockito，无 Spring 上下文）
cd fact-check && mvn test

# Python：63 个用例（标准库 unittest，零第三方依赖）
cd fact-agent && python -m unittest discover -s tests -t . -v

# AI 能力评测（离线去重评测不需要任何 Key）
py -3.10 fact-agent/eval/evaluate.py --mode dedup
# 联网判定评测需要 LLM_API_KEY 与搜索源配置
py -3.10 fact-agent/eval/evaluate.py --mode live --subset 10
```

评测结果见 [`docs/评测报告.md`](docs/评测报告.md)（含阈值敏感性图表与真实联网判定结果）。

---

## 端口与账号

| 服务 | 端口 | 地址 |
| --- | --- | --- |
| Nginx（前端入口） | 80 | <http://localhost> |
| Java 后端 | 8080 | <http://localhost:8080/api> |
| Python Agent | 8001 | <http://localhost:8001/health> |
| MySQL | 3306 | |
| Redis | 6379 | |

| 账号 | 密码 | 角色 | 来源 |
| --- | --- | --- | --- |
| `admin` | `Admin@123456` | 管理员 | `sql/fact_check_init.sql` |
| `demo_user` | `demo123` | 普通用户 | `scripts/generate_test_data.py` |

> ⚠️ 均为演示用弱口令，首次登录后请立即修改。管理员账号不能自行注册，只能由已有管理员在“账号管理”中创建。

---

## 配置说明

### Java 后端

支持两种注入方式，**优先级：环境变量 > profile 配置文件**。

- `application.yml`：公共配置（JWT、Agent 地址、OCR、去重开关、限流阈值等），不含任何真实密钥。
- `application-dev.yml`（已入库）：安全默认值 + 环境变量占位，可零配置启动（数据库用 localhost/root）。
- `application-local.yml`（**未入库**）：本地真实密钥，需激活 `local` profile。
  **默认构建会把它复制到 `target/classes`**（这样 IDEA 才能读到）；要构建不含密钥的产物请用 `mvn package -Pdist`。
- `application-local.yml.example`（已入库）：模板。

关键配置项：

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `fact-check.agent.base-url` | `http://127.0.0.1:8001` | Python Agent 地址 |
| `fact-check.agent.semantic-threshold` | `0.92` | 语义去重相似度阈值（S10，可配） |
| `fact-check.agent.exact-enabled` | `true` | 精确去重开关 |
| `fact-check.agent.semantic-enabled` | `true` | 语义去重开关 |
| `fact-check.agent.min-text-length` | `8` | 低于此长度只做精确去重 |
| `fact-check.ocr.app-code` | 空 | 阿里云 OCR AppCode，留空则图片检测直接报“OCR 未配置” |
| `fact-check.jwt.user-ttl` / `admin-ttl` | `7200000` | Token 有效期（毫秒） |

可用环境变量：`FACTCHECK_PROFILE`、`FACTCHECK_DB_HOST/PORT/NAME/USERNAME/PASSWORD`、
`FACTCHECK_REDIS_HOST/PORT/PASSWORD/DB`、`FACTCHECK_OSS_ENDPOINT/AK/SK/BUCKET`、
`FACTCHECK_WECHAT_APPID/SECRET`、`FACTCHECK_JWT_USER_SECRET/ADMIN_SECRET`、
`FACTCHECK_AGENT_BASE_URL`、`FACTCHECK_OCR_ENDPOINT/APP_CODE`。

### Python Agent

全部通过 `fact-agent/.env` 配置（模板见 `.env.example`）：

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL` | — | OpenAI 兼容 LLM |
| `LLM_TIMEOUT` / `LLM_RETRY` | `60` / `2` | 超时（秒）与重试次数 |
| `LLM_TEMPERATURE` | `0.1` | 事实核查要求稳定输出 JSON，不建议调高 |
| `SEARCH_TOOL` | `dummy` | `dummy` 不联网；联网请改 `tavily` / `serpapi` / `bocha` |
| `SEARCH_TOP_K` / `SEARCH_FETCH_TOP` | `10` / `3` | 检索条数 / 抓取正文条数 |
| `SEARCH_TIMEOUT` / `SEARCH_PAGE_TIMEOUT` | `20` / `10` | 单次搜索 / 单次抓取超时（秒） |
| `DETECT_TOTAL_BUDGET` | `45` | 单次检测的整体时间预算（秒），必须小于 Java 侧 `fact-check.agent.timeout`（默认 `90`） |
| `DETECT_MAX_TEXT_LENGTH` | `5000` | 待检测文本上限，超出截断 |
| `DEDUP_THRESHOLD` | `0.92` | 语义去重阈值 |
| `DEDUP_MIN_TEXT_LENGTH` | `8` | 最短语义去重文本长度 |
| `EMBEDDING_MODEL` | `BAAI/bge-small-zh-v1.5` | 可用 HuggingFace 名称或本地路径；**相对路径一律相对 `fact-agent/` 解析** |
| `AGENT_DATA_DIR` | `./data` | FAISS 索引目录（相对 `fact-agent/` 解析） |
| `INTERNAL_TOKEN` | 空 | 内部鉴权；留空则不校验（Java 侧目前不发送该请求头，配了会 401） |

> ⚠️ **默认 `SEARCH_TOOL=dummy`，即不联网**，此时检测退化为纯 LLM 自身知识判断。要体现"联网核查"能力，必须配置真实搜索源。
>
> ⚠️ **必须以单 worker 运行**（`uvicorn app.main:app --port 8001`，不要加 `--workers`）：语义去重索引是进程内内存态，多 worker 会各持一份并互相覆盖。

**AI 侧的几个关键设计**

- **时间预算**：单次 `/detect` 整体不超过 `DETECT_TOTAL_BUDGET`，各步骤按剩余预算分配超时，避免"Java 已判失败、Agent 仍在跑"。
- **失败语义**：`error=true` 表示服务侧故障（LLM 失败 / 输出无法解析），Java 记为**检测失败（status=3）**可重试；只有 AI 确实判断不了才转人工。
- **证据可信度**：由确定性域名规则表（`app/services/credibility.py`）决定，**不采信模型自报的可信度**；相关度按"位置 + 词面重合"估计，证据按相关度降序返回。
- **防提示词注入**：用户文本与抓取正文一律作为不可信数据放入 `<untrusted_*>` 标签，转义边界字面量，系统提示声明"仅为数据"，输出侧再做字段强制校验。

---

## 接口约定

- 统一前缀 `/api`，RESTful JSON。
- 统一返回体：`{ "code": 1, "msg": "success", "data": {} }`；分页返回 `{ "total": 100, "records": [] }`。
- 请求头：用户端 `authentication`，管理端 `token`（两者使用不同 JWT 密钥）。
- 完整接口清单见 [`docs/技术方案.md`](docs/技术方案.md) 第 6 章。

核心接口速览：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/user/register` / `/api/user/login` | 注册 / 登录（按 role 返回对应类型 token） |
| POST | `/api/user/wechat/login` / `/api/user/wechat/bind` | 小程序登录 / 绑定 Web 账号 |
| POST | `/api/detect/submit` | 提交检测（文字或图片 OCR 文本 + 分类） |
| GET | `/api/detect/record/{id}` | 查询检测记录详情（轮询用） |
| GET | `/api/hot/top10` | 热门消息 Top10 |
| POST | `/api/appeal` / `/api/rating` / `/api/favorite/{recordId}` | 申诉 / 评分 / 收藏 |
| GET | `/api/admin/pending/top10` | 待诊断 Top10（超时高亮） |
| POST | `/api/admin/records/{id}/conclusion` | 人工诊断提交结论 |
| PUT | `/api/admin/records/{id}/conclusion` | 修改历史结论（附原因，写版本快照） |
| POST | `/api/admin/appeals/{id}/handle` | 处理申诉（采纳需给新结论 + 新推理依据） |
| GET | `/api/admin/stats/overview` / `/api/admin/stats/logs` | 工作台统计 / 操作日志 |

---

## 项目状态与已知不足

项目已完成**端到端可运行**的完整链路，并有真实运行记录：

- 真实联网核查已验证：`SEARCH_TOOL=tavily` 时，检索 → 抓取正文 → LLM 判定（`FALSE`，confidence 0.91–0.95）→ 返回 6–7 条带真实域名与可信度的证据，单次耗时约 15s（预算 45s）。
- 历史上还有：检测提交 → 轮询拿到结论 → 站内信/热度榜/权威信息均为 200（见 nginx 访问日志）。

作为作品集项目仍有明确短板，已逐项记录在 [`docs/项目改进清单.md`](docs/项目改进清单.md)（按 P0–P5 分级，含证据与修复方案）。主要缺口：

- **零自动化测试**：Java / Python / 前端三个模块均无测试代码（P4-42）。
- **未接入 Docker / CI**：技术方案建议的 Docker Compose 编排尚未落地，环境搭建依赖手工步骤（P4-43）。
- **内容安全审核（S2）尚未实质实现**：`ContentSafetyServiceImpl` 目前是空实现（仅日志），图片审核未接入（P2-13）。
- **热度权重未全部接入**：查看详情（+0.5）与发布权威信息（+5）两个权重尚未生效（P2-14）。
- **限流只覆盖检测提交**：登录/注册尚未限流（P2-16）；后端尚未接入 Bean Validation 参数校验（P2-17）。
- **小程序端**：记录详情页较薄（缺证据列表/评分/收藏/申诉入口），未实现微信原生分享，无分页与未读红点（P3-30~33）。
- **来源可信度域名表较窄**：仅覆盖主流站点，省级疾控等权威机构自建域名会落到兜底值 0.3，后续应扩充或改为管理员维护。
- **内部鉴权未启用**：Python 侧支持 `X-Internal-Token`，但 Java 侧尚未发送该头（P2-21）。
- **无初始分类字典**：消息分类为代码内固定枚举，数据库无字典表（设计如此，非缺陷）。

> 已知问题均已在改进清单中排序，未做隐藏。

---

## 文档导航

| 文档 | 内容 |
| --- | --- |
| [`docs/产品文档.md`](docs/产品文档.md) | 产品定位、角色权限矩阵、功能需求、核心流程、页面清单、验收标准 |
| [`docs/技术方案.md`](docs/技术方案.md) | 架构、技术选型、数据库设计（12 张表）、Redis 设计、接口清单、Python Agent 设计、安全设计、部署架构 |
| [`docs/项目改进清单.md`](docs/项目改进清单.md) | 代码审计结果、按优先级排序的待办（P0–P5，60 项）、执行批次、面试问答准备 |
| [`fact-agent/README.md`](fact-agent/README.md) | Python Agent 的接口、环境变量与启动说明 |

---

## 开发约定

- Controller / Service 实现类使用 `@Slf4j`，关键方法入口打印参数，使用 SLF4J（不用 `printStackTrace`）。
- 所有类与方法必须有 Javadoc，说明用途。
- 返回体统一 `Result<T>`，分页统一 `PageResult`。
- 业务异常继承 `BaseException`，由 `GlobalExceptionHandler` 统一转换为 `Result`。
- Mapper 接口 + `resources/mapper/*.xml`；`map-underscore-to-camel-case` 已开启。
- 提交信息采用 `feat/fix/docs/chore(scope): 描述` 形式。
