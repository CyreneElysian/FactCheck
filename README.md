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
python -m pip install -r requirements.txt

cp .env.example .env
# 编辑 .env：至少填写 LLM_API_KEY；要联网核查请把 SEARCH_TOOL 从 dummy 改为 tavily/bocha/serpapi 并填 SEARCH_API_KEY

python -m uvicorn app.main:app --host 0.0.0.0 --port 8001
```

> 命令一律用 `python -m <模块>`（`python -m pip` / `python -m uvicorn` / `python -m app.main`）而不是
> `uvicorn.exe` / `pip.exe` 这类启动器：**Windows 的 venv 不支持移动目录**，
> 一旦项目文件夹被改名或移动，`Scripts\*.exe` 里内嵌的旧解释器绝对路径就会全部失效，报
> `Fatal error in launcher: Unable to create process using "...\<旧路径>\.venv\Scripts\python.exe"`。
> 用 `python -m` 不经过这些转发器，因此换目录后依然可用（前提是在 venv 里执行 `python`）。
> 彻底修复则需要重建 venv：删掉 `.venv` 后在**当前**目录重新 `python -m venv .venv` 并重装依赖。
>
> 另外：`python -m app.main` 也等价于上面的 uvicorn 命令（监听地址与端口取自 `.env` 的 `AGENT_HOST`/`AGENT_PORT`）。

> 首次调用 `/dedup` 或 `/index/add` 会下载并加载 embedding 模型，耗时较长。
>
> ⚠️ **不要加 `--workers`**：语义去重索引是进程内内存态，多 worker 会各持一份并互相覆盖。
>
> 启动日志会自检搜索源与 LLM 配置：若为 `dummy` 会明确告警"不联网"，缺 Key 会报错，便于第一时间发现"看起来能跑、实际没联网"。
> 判断是否真的起来了：日志里必须出现 **`Uvicorn running on http://...`**。

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
# Java：224 个用例（JUnit 5 + Mockito，无 Spring 上下文）
cd fact-check && mvn test

# Python：111 个用例（标准库 unittest，零第三方依赖）
cd fact-agent && python -m unittest discover -s tests -t .

# 小程序静态自检：110 项（JS 语法 / JSON / 页面齐套 / 跳转目标 / 事件绑定）
cd fact-check-mini && python check_mini.py

# 前端单元测试：需要先 npm i（离线装不上 vitest）
cd fact-check-web && npm run test:run      # vitest
cd fact-check-web && npm run test:offline  # 零依赖兜底：同一批 spec，只用 node 跑
cd fact-check-web && npm run type-check    # vue-tsc（含模板类型检查）
cd fact-check-web && npm run lint          # 需要先 npm i（eslint / prettier）
```

**一条命令跑完全部检查**（等价于 CI 流水线，可直接当提交前钩子）：

```powershell
cd fact-check
.\run-checks.ps1              # java / python / mini / web-type / web-test / compose / secret-scan
.\run-checks.ps1 -Only java,web
.\run-checks.ps1 -Online      # Maven 走在线仓库（默认 -o 离线）
```

> 本项目的 CI 文件是 GitHub Actions（[`.github/workflows/ci.yml`](.github/workflows/ci.yml)），
> 而仓库托管在 **Gitee**——Gitee 不执行 GitHub Actions，因此日常用 `run-checks.ps1` 在本地跑同样这七项
> （实机验证：7 项全 PASS、退出码 0）。想在 Gitee 上跑托管流水线需改用 Gitee Go，
> 具体步骤见 [`docs/环境限制与联网后待办.md`](docs/环境限制与联网后待办.md) 第三节。

AI 能力评测（离线去重评测不需要任何 Key）：

```bash
py -3.10 fact-agent/eval/evaluate.py --mode dedup
# 联网判定评测需要 LLM_API_KEY 与搜索源配置
py -3.10 fact-agent/eval/evaluate.py --mode live --subset 10
```

> **关于 `test:offline`**：本机处于离线环境（npm registry 不可达、缓存里也没有 vitest），
> 于是写了一个约 60 行的零依赖运行器（`scripts/vitest-shim.mjs` + `spec-loader.mjs`），
> 把 spec 里的 `vitest` 与 `@/` 解析到本地实现——**同一批 spec 两种方式都能跑**，不存在两份断言。
> 它的价值是真实的：写完后立刻跑出过一个 `ws:////host`（四斜杠）的拼接 bug。
> 装好 vitest 后请用 `npm run test:run`（支持组件渲染、覆盖率、watch）。

评测结果见 [`docs/评测报告.md`](docs/评测报告.md)（含阈值敏感性图表与真实联网判定结果）。

### 10. 一键启动（可选）

```powershell
.\start-all.ps1 check    # 环境自检：工具链 / 端口 / MySQL / Redis / nginx / 内部令牌
.\start-all.ps1 all      # 依次启动 Agent(8001) 与 Java(8080)，并等待就绪
.\start-all.ps1 stop     # 停止由脚本启动的进程
```

> 脚本用 `-WindowStyle Hidden` 启动子进程。若你要**捕获脚本输出**（如 `.\start-all.ps1 all > logs\start.log`），
> 请重定向到文件而不是管道：子进程会继承 stdout 管道，用 `|` 时调用方要等服务退出才会拿到输出。

### 11. 容器化部署（可选，推荐）

```bash
docker compose up -d --build     # MySQL + Redis + Agent + Java + nginx 五个服务
docker compose logs -f server
docker compose down              # 停止（加 -v 会同时清空数据卷）
```

首次启动需要网络（拉基础镜像、容器内装 Maven/pip 依赖、下载 embedding 模型）；
之后可完全离线。密钥通过宿主机环境变量注入（见 `docker-compose.yml` 里的 `FACTCHECK_*`），
Agent 与 Java 的**内部令牌由同一个 compose 变量提供**，不会出现两侧不一致导致全量 401 的情况。

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
| `INTERNAL_TOKEN` | `dev-internal-token-change-me` | 内部鉴权；**必须与 Java 侧 `fact-check.agent.internal-token` 一致**，留空则内部接口一律 401（fail-closed） |
| `CREDIBILITY_RULES_FILE` | 空 | 来源可信度规则库路径；留空用 `fact-agent/config/credibility_rules.json`（改词表不用改代码） |

> ⚠️ **默认 `SEARCH_TOOL=dummy`，即不联网**，此时检测退化为纯 LLM 自身知识判断。要体现"联网核查"能力，必须配置真实搜索源。
>
> ⚠️ **必须以单 worker 运行**（`python -m uvicorn app.main:app --port 8001`，不要加 `--workers`）：语义去重索引是进程内内存态，多 worker 会各持一份并互相覆盖。

**AI 侧的几个关键设计**

- **时间预算**：单次 `/detect` 整体不超过 `DETECT_TOTAL_BUDGET`，各步骤按剩余预算分配超时，避免"Java 已判失败、Agent 仍在跑"。
- **失败语义**：`error=true` 表示服务侧故障（LLM 失败 / 输出无法解析），Java 记为**检测失败（status=3）**可重试；只有 AI 确实判断不了才转人工。
- **证据可信度**：由确定性域名规则表（`app/services/credibility.py`）决定，**不采信模型自报的可信度**；相关度同样只用一种确定性口径（`位置分 × (0.3 + 0.7 × 词面重合)`，乘性组合），证据按相关度降序返回。
- **防提示词注入**：用户文本与抓取正文一律作为不可信数据放入 `<untrusted_*>` 标签，转义边界字面量，系统提示声明"仅为数据"，输出侧再做字段强制校验。

---

## 接口约定

- 统一前缀 `/api`，RESTful JSON。
- 统一返回体：`{ "code": 1, "msg": "success", "data": {} }`；分页返回 `{ "total": 100, "records": [] }`。
- 请求头：用户端 `authentication`，管理端 `token`（两者使用不同 JWT 密钥）。
- **在线接口文档（knife4j）**：启动后访问 <http://localhost:8080/api/doc.html>，
  按「用户端接口 / 管理端接口」两个分组，可在页面上填 token 直接调接口。
  分组 OpenAPI JSON：`/api/v3/api-docs?group=用户端接口`；分组列表：`/api/swagger-resources`。
  打开文档本身不需要登录；不带 `group` 参数访问 `/api/v3/api-docs` 会 404（只有中文命名分组，没有 `default`）。
- 完整接口清单另见 [`docs/技术方案.md`](docs/技术方案.md) 第 6 章（作为不依赖运行时的手册）。

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

作为作品集项目，原本的短板已逐项处理完毕：**[`docs/项目改进清单.md`](docs/项目改进清单.md) 中的 75 项全部处理完毕（74 项实现 + 1 项经决策明确不做）**。当前仍有以下限制与取舍，如实列出；每项的**具体补做步骤**见 [`docs/环境限制与联网后待办.md`](docs/环境限制与联网后待办.md)：

- **前端 vitest / eslint 尚未在真实工具链下跑过**：配置与用例都已写好并声明在 `package.json`，
  但本机 npm registry 不可达、缓存也没有这些包（实测 `ENOTCACHED`）。已用零依赖运行器
  `npm run test:offline` 跑**同一批 spec**（17 条断言通过，并当场抓出过一个 `ws:////host` 拼接 bug）——
  未验证的只是 vitest 这层框架接线。Java（224 用例）、Python（111 用例）、
  小程序静态自检 [`check_mini.py`](fact-check-mini/check_mini.py)（110 项）均已在本机实跑通过。
- **Docker Compose 未做过真实构建与启动**：编排文件、两个 Dockerfile 与容器版 nginx 已就位，
  `docker compose config` 校验通过、所有构建上下文/挂载源逐项校验存在、两侧内部令牌由同一变量提供；
  但本机只装了 Docker CLI（引擎未启动、零镜像），起整套需联网拉约 2–3GB 基础镜像。
- **CI 已接入但未在 GitHub 上运行过**：[`.github/workflows/ci.yml`](.github/workflows/ci.yml) 五个作业
  （java / python / web / mini / secret-scan），命令全部与本机验证过的一致；push 后在 Actions 页可见。
- **分享卡片不含二维码（已决策不做）**：记录详情仅归属者可见，扫码的人打开仍是登录页，与直接收到链接相比没有增量价值；
  小程序端已有微信原生分享。产品文档 §4.13 已同步修订，理由与"将来要补做"的方案见
  [`docs/决策-分享卡片不生成二维码.md`](docs/决策-分享卡片不生成二维码.md)。
- **前端主包偏大（已知优化点）**：`vite build` 已在本机验证成功（4.14s，35 个 assets），
  但 `index-*.js` 达 1,117 kB（gzip 370 kB）——element-plus 与 vue 全进主包。
  按 `docs/环境限制与联网后待办.md` 第四节的 `manualChunks` 片段拆包即可显著改善首屏。
- **来源可信度域名库覆盖仍偏窄**：规则已外置为 `config/credibility_rules.json`（改词表不用改代码），
  但内容仍是起步版——省级疾控等权威机构的自建域名会落到兜底值 0.3（语义是"未收录"而非"不可信"），需要持续补充。
- **参数校验未使用 Bean Validation**：因离线仓库只有 `jakarta` 版 hibernate-validator（与 Boot 2.7 的
  `javax` 命名空间不兼容），改用自研的 `ValidationUtils`；联网环境可机械替换为 `@Valid` 注解（详见改进清单 P2-17）。
- **记录详情刻意不做缓存**：状态会异步变化（AI 判定/人工诊断/申诉改判），缓存脏读风险大于收益（P2-28 决策）。
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
