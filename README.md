
# AI智能体助手 - JChatMind

JChatMind 是一个智能 AI Agent 系统，基于 Spring AI 框架构建，实现了自主决策、工具调用和知识库检索等核心能力。

系统采用 **Think-Execute 循环机制**，能够理解复杂任务、规划执行步骤、调用外部工具，并基于 RAG 技术从知识库中检索相关信息，完成多步骤的复杂任务。

它不是"聊天机器人"，而是 Agent：**能规划、能调用工具、能检索知识库、还能把执行过程实时推给前端**。

---

## 项目亮点

### 1. 真正的 Agent Loop（Think-Execute 循环 + 状态机）

不是"调用一次大模型就结束"，而是支持：

* 多轮规划
* 多轮工具调用
* 状态管理（THINKING / EXECUTING / DONE / ERROR）
* 错误处理与最大步数控制（防止无限循环）

### 2. 工具系统（固定工具 + 可选工具，可扩展、可治理）

工具系统采用"框架化"设计：

* 工具自动注册
* 固定工具 / 可选工具分类管理
* 可扩展：新增工具不改核心流程
* 可控：禁用 Spring AI 自动执行，改为手动管理 ToolCalling 流程

### 3. RAG 知识库（PostgreSQL + pgvector）

完整实现 RAG 全链路：

* Markdown 文档解析、分块
* Embedding 生成并落库
* pgvector 相似度检索（`<->`）
* ivfflat 索引优化，支持 10 万+向量

用 PostgreSQL 一套体系把结构化数据和向量数据都管了（部署简单、成本低、事务一致性好）。

### 4. 多模型支持（注册表模式 ChatClientRegistry）

* DeepSeek / 智谱 AI 可切换
* 统一 ChatClient 接口
* 注册表模式管理模型实例（解耦创建与使用）
* 便于未来扩展更多模型

### 5. SSE 实时通信（执行过程实时可视化）

* 状态实时推送：THINKING / EXECUTING / DONE
* 前端能实时看到"Agent 正在干啥"
* 比 WebSocket 更简单，适合单向推送

---

## 技术栈

| 层次 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3.5 + Spring AI 1.1 |
| AI 模型 | DeepSeek、智谱 GLM |
| 数据库 | PostgreSQL 17 + pgvector |
| ORM | MyBatis |
| 前端 | React 19 + Vite + TypeScript + Ant Design |
| 实时通信 | SSE（Server-Sent Events） |

---

## 项目架构

JChatMind 通过分层架构 + Agent 核心服务，把 AI 能力（模型、RAG、工具）抽象成可组合、可扩展的系统模块。

---

## 快速开始

### 环境要求

* JDK 17
* PostgreSQL 17 + pgvector 扩展
* Node.js 18+
* Maven（或使用项目内置的 mvnw）

详细的环境配置指南请参考 [environment.md](./environment.md)

### 启动后端

```bash
cd jchatmind
./mvnw spring-boot:run
```

### 启动前端

```bash
cd ui
npm install
npm run dev
```

---

## License

[MIT](./LICENSE)
