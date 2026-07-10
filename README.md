# JChatMind

JChatMind 是一个基于 Spring AI 构建的可配置 AI Agent 系统。它支持多轮自主决策、手动编排工具调用、知识库检索、长期记忆，以及通过 SSE 将 Agent 执行过程实时推送到 React 前端。

## 当前能力

- **Agent Loop**：以 Think-Execute 循环驱动模型决策、工具调用、工具结果回填和最终回答，内置最大步数与状态控制。
- **模型与工具治理**：通过 `ChatClientRegistry` 管理 DeepSeek、智谱 GLM 等模型；工具由注册表统一管理，并按 Agent 配置限制可调用工具和知识库。
- **实时对话体验**：前端通过 SSE 接收思考、工具执行、回答完成等事件，并展示工具调用和知识库命中信息。
- **长期记忆**：支持全局用户记忆和 Agent 专属记忆；新建 Agent 时可开启后台自动记忆，模型会根据一段对话自主判断新增、更新或删除记忆。
- **文档知识库**：支持 Markdown、TXT、PDF、DOCX、PPTX、XLSX、HTML、CSV、JSON、XML 上传，单文件默认最大 150MB。
- **RAG 检索链路**：使用 `bge-m3` 生成向量、PostgreSQL + pgvector 做粗召回、`bge-reranker-v2-m3` 做 cross-encoder 重排，再进行去重、文档维度限流和 TopK 筛选。

## 技术栈

| 层次 | 技术 |
| --- | --- |
| 后端 | Spring Boot 3.5、Spring AI 1.1、MyBatis |
| 前端 | React 19、Vite、TypeScript、Ant Design |
| 大模型 | DeepSeek、智谱 GLM（可扩展） |
| 向量与数据库 | Ollama `bge-m3`、PostgreSQL 17、pgvector |
| 重排模型 | BAAI `bge-reranker-v2-m3`、FlagEmbedding |
| 文档转换 | MarkItDown |
| 实时通信 | SSE |

## RAG 工作流

```text
上传文档
  -> MarkItDown 转换（非 Markdown 文件）
  -> Markdown 分层标题分块 / 非 Markdown 定长重叠分块
  -> bge-m3 embedding 入库（chunk_bge_m3）

用户问题
  -> bge-m3 embedding
  -> pgvector 粗召回 rawTopK=20
  -> bge-reranker-v2-m3 重排
  -> 去重、距离过滤、单文档上限、finalTopK=3
  -> KnowledgeTool 将命中片段作为上下文交给 Agent
```

`reranker` 是当前检索链路的必需服务。服务不可用、超时或返回非法结果时，检索会直接报错，**不会降级到旧的 Hybrid 重排**，以免在不知情的情况下使用另一套排序策略。

## 快速开始（macOS）

### 1. 前置环境

| 依赖 | 作用 | 建议版本 |
| --- | --- | --- |
| JDK | 运行后端 | 17 |
| PostgreSQL + pgvector | 业务数据、记忆、向量存储 | PostgreSQL 17 |
| Node.js | 运行前端 | 18+ |
| Ollama | `bge-m3` embedding 服务 | 最新稳定版 |
| uv + Python | MarkItDown 和 reranker 运行时 | Python 3.10+ |

```bash
# 基础依赖
brew install postgresql@17 pgvector node uv
brew services start postgresql@17

# 可从 Ollama 官网安装 macOS App，也可用 Homebrew 安装二选一
brew install ollama
brew services start ollama
ollama pull bge-m3

# 多格式文档转换
uv tool install 'markitdown[all]'
markitdown --version
```

如果通过 Ollama 官网 DMG 安装，跳过 `brew install ollama`，确保 Ollama App 已启动并执行 `ollama pull bge-m3` 即可。

### 2. 初始化数据库

```bash
createdb jchatmind
psql -d jchatmind -f jchatmind_assert/jchatmind.sql
```

`jchatmind_assert/jchatmind.sql` 是新环境的完整初始化脚本，包含 pgvector、Agent 自动记忆、用户记忆和知识库相关表结构。已有旧数据库升级时，请先备份数据，再按实际缺失的版本执行 `jchatmind_assert/` 下的增量 SQL。

### 3. 配置环境变量

将以下内容写入 `~/.zshrc`，再执行 `source ~/.zshrc`：

```bash
# Database
export DB_URL=jdbc:postgresql://localhost:5432/jchatmind
export DB_USERNAME=postgres
export DB_PASSWORD=

# LLM: 至少配置你准备在 Agent 中使用的一个模型
export DEEPSEEK_API_KEY=your_deepseek_api_key
export ZHIPUAI_API_KEY=your_zhipuai_api_key

# Document conversion
export MARKITDOWN_COMMAND=markitdown

# Optional: override the default reranker endpoint or document storage directory
# export RAG_RERANK_BGE_ENDPOINT=http://localhost:8001/rerank
# export DOCUMENT_STORAGE_BASE_PATH=./data/documents
```

完整可配置项见 [application-example.yaml](./jchatmind/src/main/resources/application-example.yaml)。本地上传的原始文件与转换结果默认写入 `jchatmind/data/documents`，属于运行时数据，不应提交到 Git。

### 4. 启动 reranker 服务

在项目根目录新开终端执行：

```bash
uv run --with FlagEmbedding --with 'transformers==4.44.2' \
  scripts/rag_bge_reranker_server.py --device mps --port 8001
```

首次请求会下载并加载 `BAAI/bge-reranker-v2-m3`。启动后可以检查：

```bash
curl http://127.0.0.1:8001/health
```

Apple Silicon 使用 `--device mps`；其他环境可以改为 `--device cpu`。脚本会串行化模型推理，避免 MPS 并发推理不稳定。

### 5. 启动后端和前端

```bash
# 终端 1：后端，默认 http://localhost:8080
cd jchatmind
./mvnw spring-boot:run

# 终端 2：前端，默认 http://localhost:5173
cd ui
npm install
npm run dev
```

浏览器访问 <http://localhost:5173>。

推荐启动顺序：PostgreSQL -> Ollama -> reranker -> 后端 -> 前端。

## 服务检查

```bash
# PostgreSQL
pg_isready

# Ollama embedding
curl http://127.0.0.1:11434/api/embeddings \
  -d '{"model":"bge-m3","prompt":"hello"}'

# BGE reranker
curl http://127.0.0.1:8001/health
```

| 服务 | 默认地址 | 用途 |
| --- | --- | --- |
| 前端 | `http://localhost:5173` | React UI |
| 后端 | `http://localhost:8080` | REST API / SSE |
| Ollama | `http://localhost:11434` | embedding |
| reranker | `http://localhost:8001` | RAG 重排 |
| PostgreSQL | `localhost:5432` | 数据与向量 |

## 开发验证

```bash
# 后端测试与编译
cd jchatmind
./mvnw test
./mvnw -DskipTests compile

# 前端构建
cd ui
npm run build
```

更多 RAG 设计、评测结果和记忆升级文档见 [docs/README.md](./docs/README.md)。

## License

[MIT](./LICENSE)
