# JChatMind Docs Index

这个目录目前主要分为三类内容：系统升级方案、实现记录、离线评测产物。

## 推荐阅读顺序

1. `rag-system-upgrade.md`
   - RAG 系统升级的主方案文档。
   - 重点看多格式导入、分块策略、召回可观测、Hybrid Rerank、离线评测设计。

2. `rag-system-upgrade-implementation-report.md`
   - RAG 升级完成后的实施报告。
   - 用来对照代码改造点、测试情况和离线评测结果。

3. `agent-memory-upgrade/core-vs-retrieved-memory.md`
   - Agent Memory 的核心分类标准。
   - 明确 Core Memory 和 Retrieved Memory 的职责边界。

4. `agent-memory-upgrade/auto-agent-memory-design.md`
   - Agent 自动记忆的设计方案。
   - 重点看创建时配置、异步整理流程、job state 幂等机制和记忆提取 prompt。

5. `memory-system-upgrade.md`
   - 早期记忆系统整体升级方案。
   - 当前保留为历史方案参考；后续以 `agent-memory-upgrade/` 下的文档为准。

## 目录说明

### `agent-memory-upgrade/`

Agent 记忆系统升级相关文档。

- `core-vs-retrieved-memory.md`
  - 当前有效。
  - 定义 Agent Core Memory 和 Agent Retrieved Memory 的分类边界。

- `auto-agent-memory-design.md`
  - 当前有效。
  - 描述自动记忆开关、记忆整理间隔、异步提取、入库和 job state 更新逻辑。

### `rag-evaluation/`

RAG 离线评测产物目录。这里的数据文件较多，主要用于复现实验。

- `mysql-rag-eval-*`
  - 早期 PDF RAG 评测产物。
  - 使用 PDF 文本分块和自动构造问题。

- `mysql-pdf-chunking/`
  - 图解 MySQL PDF 转 Markdown 后的分块验证产物。
  - 用于检查 PDF plain text chunking 的效果。

- `current-chunking-comparison/`
  - 当前正式分块策略下，纯向量检索与 Hybrid Rerank 的对比实验。

- `bge-reranker-comparison/`
  - `bge-reranker-v2-m3` 与当前 Hybrid Rerank 的对比实验。
  - 注意：该实验基于偏原文线索型测试集，结论不能直接代表真实用户提问场景。

- `easy-langent-natural/`
  - 基于 `前言.md` 构造自然问题测试集的临时实验目录。
  - 当前数据集仍在筛选质量阶段，不建议作为正式评测结论来源。

## 当前建议

### 建议保留

- `rag-system-upgrade.md`
- `rag-system-upgrade-implementation-report.md`
- `agent-memory-upgrade/core-vs-retrieved-memory.md`
- `agent-memory-upgrade/auto-agent-memory-design.md`
- `rag-evaluation/current-chunking-comparison/*-report.md`
- `rag-evaluation/current-chunking-comparison/*-summary.json`
- `rag-evaluation/bge-reranker-comparison/*-report.md`
- `rag-evaluation/bge-reranker-comparison/*-summary.json`

### 建议归档或降级为实验数据

- `memory-system-upgrade.md`
  - 作为早期总方案保留，但不再作为当前记忆系统实现口径。

- `rag-evaluation/mysql-rag-eval-*`
  - 早期评测产物，适合归档。

- `rag-evaluation/mysql-pdf-chunking/mysql-xiaolin.md`
  - PDF 转换后的大文本，属于实验输入，不适合长期放在主文档阅读路径。

- `rag-evaluation/mysql-pdf-chunking/mysql-xiaolin-chunks-preview.md`
  - 分块预览文件，属于临时验证材料。

- `rag-evaluation/easy-langent-natural/easy-langent-natural-dataset-preview.jsonl`
  - 预览数据，质量不稳定，可在正式数据集定稿后删除。

### 建议删除

- `rag-evaluation/.DS_Store`
  - macOS 自动生成文件，没有项目价值。

## 后续整理方向

如果要进一步整理目录，可以调整为：

```text
docs/
  README.md
  rag/
    rag-system-upgrade.md
    rag-system-upgrade-implementation-report.md
    evaluation/
      current-chunking-comparison/
      bge-reranker-comparison/
      archive/
  memory/
    core-vs-retrieved-memory.md
    auto-agent-memory-design.md
    archive/
      memory-system-upgrade.md
```

当前先保留原路径，避免影响已有脚本和报告里的相对路径。
