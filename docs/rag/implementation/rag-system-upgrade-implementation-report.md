# JChatMind RAG 系统升级实施报告

## 1. 基准信息

本轮改造基于以下提交开始：

```text
2f2b5c454d8fdc72e19299971972bbe83fb22a77
```

如果需要回滚到改造前状态，可以回到该提交。

本轮只做本地阶段提交，未 push。

阶段提交：

```text
3727a6b Add observable RAG retrieval
6dde80b Add configurable RAG reranking
9e3f5e4 Add offline RAG evaluation test
e9a3723 Show RAG retrieval hits in chat UI
a89a313 Scope KnowledgeTool to agent knowledge bases
```

## 2. 改造目标对齐

本次改造严格按 `docs/rag-system-upgrade.md` 的 Phase 2 到 Phase 5 继续推进。

已经完成：

- Phase 2：RAG 召回可观测。
- Phase 3：可配置检索参数、基础过滤和空结果处理。
- Phase 4：可配置 rerank。
- Phase 5：工具返回引用信息，前端展示命中文档、chunk 和分数。
- 补充安全约束：`KnowledgeTool` 强制校验 `kbsId` 属于当前 Agent 绑定知识库。

保留策略：

- `max-distance` 默认仍为空，不拍脑袋写死阈值。
- 阈值过滤能力已经实现，可以通过环境变量启用。
- rerank 采用本地轻量 hybrid rerank，避免第一版引入外部 rerank 服务依赖。

## 3. 后端改造

### 3.1 结构化检索结果

新增：

- `RagSearchResult`
- `RagSearchResponse`

检索结果现在包含：

- `chunkId`
- `knowledgeBaseId`
- `documentId`
- `documentTitle`
- `content`
- `metadata`
- `distance`
- `score`
- `lexicalScore`
- `rerankScore`
- `filtered`
- `filterReason`
- `rank`

### 3.2 Mapper 返回 distance 和文档来源

`ChunkBgeM3Mapper.similaritySearchDetailed()` 现在通过 pgvector 返回：

```sql
c.embedding <-> queryEmbedding AS distance
1.0 / (1.0 + distance) AS score
```

并关联 `document` 表得到 `documentTitle`。

### 3.3 检索策略

新增 `RagRetrievalPolicy`，实现：

- 空内容过滤。
- `max-distance` 过滤。
- 重复内容过滤。
- 单文档最多命中数量限制。
- `finalTopK` 截断。
- 过滤原因可观测。

配置项：

```yaml
rag:
  retrieval:
    raw-top-k: 10
    final-top-k: 3
    max-distance:
    max-chunks-per-document: 2
    debug-enabled: true
```

### 3.4 Rerank

新增 `RagReranker`。

当前采用 hybrid score：

```text
rerankScore = vectorWeight * vectorScore + lexicalWeight * lexicalScore
```

默认配置：

```yaml
rag:
  rerank:
    enabled: true
    vector-weight: 0.70
    lexical-weight: 0.30
```

其中：

- `vectorScore` 来自 pgvector distance 转换。
- `lexicalScore` 基于 query 与文档标题、metadata、chunk 内容的词面重合。
- 中文场景使用 CJK 单字和 bigram 特征。
- 英文场景使用字母数字 token。

### 3.5 KnowledgeTool 返回结构化文本

`KnowledgeTool` 不再返回简单的 `String.join("\n", chunks)`，而是返回：

- 检索问题。
- 命中文档。
- `documentId`
- `chunkId`
- `distance`
- `score`
- `rerankScore`
- 片段内容。
- 使用规则。

当无有效片段时，会明确返回：

```text
知识库中没有找到与当前问题足够相关的内容。
请不要基于知识库编造答案，可以基于通用知识回答并说明未找到知识库依据。
```

### 3.6 Agent 知识库权限校验

`JChatMindFactory` 创建运行时工具时，会将 `KnowledgeTool` scope 到当前 Agent 绑定的知识库。

即使模型编造或误传其他 `kbsId`，工具层也会拒绝：

```text
错误：当前 Agent 无权访问知识库 ...
```

这补齐了方案中“后端仍然必须校验 kbsId 属于当前 Agent 绑定知识库”的要求。

### 3.7 Chunk 入库优化

文档摄取时，embedding 内容从仅使用 section title 改为：

```text
标题 + 正文
```

chunk metadata 改为由 `ObjectMapper` 生成 JSON，包含：

- `documentTitle`
- `sectionTitle`
- `headingPath`
- `sourceFileType`
- `originalFileName`
- `chunkIndex`

这样后续引用、调试和评估都可以追踪来源。

## 4. 前端改造

聊天历史里的 `KnowledgeTool` 工具结果现在会解析并展示为结构化命中列表。

展开工具结果后可以看到：

- 文档名。
- `chunkId`
- `distance`
- `score`
- `rerankScore`
- chunk 内容。

这对应方案中的“前端展示命中文档、片段和分数”。

## 5. 测试情况

后端测试：

```bash
cd jchatmind
./mvnw test
```

结果：

```text
Tests run: 17, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

覆盖内容：

- Spring 上下文启动。
- 原有工具调用示例。
- 数据库工具只读保护。
- `RagRetrievalPolicy` 过滤策略。
- `RagReranker` hybrid rerank。
- `RagContextRenderer` 工具上下文渲染。
- `KnowledgeTool` Agent 知识库权限校验。
- 离线 RAG 指标评估。

前端构建：

```bash
cd ui
npm run build
```

结果：

```text
✓ built
```

说明：

- 构建通过。
- Vite 仍有原本的大 chunk 警告，不影响本次 RAG 改造。

## 6. 离线评估结果

新增 `RagOfflineEvaluationTest`，构造 5 个标注查询，每个查询 5 个候选 chunk，其中 2 个为相关 chunk。

评估口径：

- Precision@3
- Recall@3
- HitRate@1
- MRR

对比结果：

| 指标 | 纯向量排序 | Hybrid Rerank 后 | 提升 |
| --- | ---: | ---: | ---: |
| Precision@3 | 33.3% | 66.7% | +33.4 pct |
| Recall@3 | 50.0% | 100.0% | +50.0 pct |
| HitRate@1 | 0.0% | 100.0% | +100.0 pct |
| MRR | 33.3% | 100.0% | +66.7 pct |

解释：

- 该评估是可重复的离线标注集，用于验证 rerank 机制能把更相关的 chunk 提到前面。
- 它不代表真实生产知识库上的最终数值。
- 真实生产阈值仍需要在实际文档和真实 query 上采样标注后确定。

## 7. 运行时可观测性

当 `rag.retrieval.debug-enabled=true` 时，服务端日志会记录：

- `kbId`
- `query`
- `rawTopK`
- `finalTopK`
- `maxDistance`
- `rerankEnabled`
- `chunkId`
- `documentId`
- `distance`
- `score`
- `lexicalScore`
- `rerankScore`
- `filtered`
- `filterReason`

这能支持后续人工标注 distance 分布，并决定是否启用 `max-distance`。

## 8. 后续真实数据校准建议

下一步如果要进一步把 RAG 质量做实，建议用真实知识库继续执行：

1. 准备 20-50 个真实用户问题。
2. 为每个问题人工标注相关 chunk。
3. 导出当前 topK 的 `distance / score / rerankScore`。
4. 观察相关和不相关样本分布。
5. 决定生产 `RAG_RETRIEVAL_MAX_DISTANCE`。
6. 对比启用阈值前后的漏召回和噪声比例。

当前代码已经具备这一步需要的检索日志、结构化结果、过滤器和评估口径。
