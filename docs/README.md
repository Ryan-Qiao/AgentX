# JChatMind Docs Index

这个目录目前主要分为两大部分：`memory` 和 `rag`。

## 推荐阅读顺序

1. `memory/plan/core-vs-retrieved-memory.md`
   - Agent Memory 的核心分类标准。
   - 明确 Core Memory 和 Retrieved Memory 的职责边界。

2. `memory/plan/auto-agent-memory-design.md`
   - Agent 自动记忆的设计方案。
   - 重点看创建时配置、异步整理流程、job state 幂等机制和记忆提取 prompt。

3. `rag/plan/rag-system-upgrade.md`
   - RAG 系统升级的主方案文档。
   - 重点看多格式导入、分块策略、召回可观测、RAG 重排和离线评测设计。

4. `rag/implementation/rag-system-upgrade-implementation-report.md`
   - RAG 升级完成后的实施报告。
   - 用来对照代码改造点、测试情况和离线评测结果。

## 目录说明

### `memory/`

Agent 记忆系统相关文档。

- `plan/`
  - `core-vs-retrieved-memory.md`
  - `auto-agent-memory-design.md`

- `archive/`
  - 早期或已弃用的记忆方案，后续如果有旧稿再放这里。

### `rag/`

RAG 相关文档和评测产物。

- `plan/`
  - `rag-system-upgrade.md`

- `implementation/`
  - `rag-system-upgrade-implementation-report.md`

- `evaluation/`
  - `current-chunking-comparison/`
  - `bge-reranker-comparison/`
  - `easy-langent-natural/`

- `archive/`
  - `easy-langent-natural-smoke/`
  - `mysql-pdf-chunking/`
  - `mysql-rag-eval/`

## 当前建议

### 建议保留

- `memory/plan/core-vs-retrieved-memory.md`
- `memory/plan/auto-agent-memory-design.md`
- `rag/plan/rag-system-upgrade.md`
- `rag/implementation/rag-system-upgrade-implementation-report.md`
- `rag/evaluation/current-chunking-comparison/*-report.md`
- `rag/evaluation/current-chunking-comparison/*-summary.json`
- `rag/evaluation/bge-reranker-comparison/*-report.md`
- `rag/evaluation/bge-reranker-comparison/*-summary.json`
- `rag/evaluation/easy-langent-natural/easy-langent-natural-final-report.md`
- `rag/evaluation/easy-langent-natural/easy-langent-score-threshold-analysis.md`

### 建议归档

- `rag/archive/easy-langent-natural-smoke/*`
- `rag/archive/mysql-pdf-chunking/*`
- `rag/archive/mysql-rag-eval/*`

### 建议删除

- `docs/.DS_Store`

