# FactCheck Python Agent

事实检测项目的 Python Agent 服务，提供语义去重、LLM 事实检测能力。

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /health | 健康检查 |
| POST | /dedup | 语义去重 |
| POST | /index/add | 新增向量索引 |
| POST | /detect | LLM 事实检测 |

## 安装与启动（推荐 Python 3.10）

```bash
cd fact-agent
python -m venv venv
# Windows:
venv\Scripts\activate
# Linux/Mac:
source venv/bin/activate

pip install -r requirements.txt

# 方式一：直接运行
uvicorn app.main:app --host 0.0.0.0 --port 8001

# 方式二：拷贝 .env.example 为 .env 并修改配置后，可安装 python-dotenv 加载环境变量
```

## 环境变量

见 `.env.example`。主要配置：
- `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL`：OpenAI 兼容 LLM
- `LLM_RETRY`：LLM 调用失败重试次数，默认 2
- `SEARCH_TOOL`：`dummy`（不联网，本地联调）/ `tavily` / `serpapi` / `bocha`
- `SEARCH_FETCH_TOP`：抓取网页正文的前 N 条结果数量，默认 2；设为 0 关闭
- `DEDUP_THRESHOLD`：语义去重阈值，默认 0.92
- `EMBEDDING_MODEL`：默认 `BAAI/bge-small-zh-v1.5`

## Java 调用约定

Java 通过 HTTP 调用，默认地址 `http://127.0.0.1:8001`。若配置了 `INTERNAL_TOKEN`，请求头需携带 `X-Internal-Token`。

## 说明

- 首次调用 `/dedup` 或 `/index/add` 时会自动下载并加载 Embedding 模型，耗时较长。
- 未配置真实搜索源时，`SEARCH_TOOL=dummy`，LLM 将主要依赖自身知识判断。
