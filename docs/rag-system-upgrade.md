# JChatMind RAG 系统升级技术方案

## 1. 背景和目标

JChatMind 当前已经具备基础知识库能力：

```text
上传 Markdown 文档
  ↓
解析 Markdown 章节
  ↓
生成 embedding
  ↓
写入 pgvector
  ↓
Agent 绑定知识库
  ↓
KnowledgeTool 执行相似性检索
```

这套链路可以完成最小可用的 RAG，但距离稳定可用还有两个核心问题：

- 文档摄取能力弱：当前主要支持 Markdown，无法覆盖 PDF、Word、PPT、Excel、HTML、CSV 等常见知识文件。
- 召回质量不可控：当前检索结果缺少分数、阈值、rerank、引用和可观测信息，无法判断召回内容是否真的与用户问题相关。

一句话目标：

> 将 JChatMind 的知识库从“只能上传 Markdown 的基础向量检索”，升级为“支持多格式文档导入、召回过程可观测、召回质量可实验优化、答案可追溯”的 RAG 系统。

## 2. 设计原则

### 2.1 先优化 RAG，再迁移到记忆检索

RAG 检索和长期记忆检索本质上是同类问题：

```text
用户问题
  ↓
检索候选上下文
  ↓
过滤不相关内容
  ↓
注入 prompt
  ↓
生成回答
```

如果知识库 RAG 还不能保证召回相关性，直接做 Memory Retrieved 只会把问题放大。因此当前优先级是：

```text
先优化知识库 RAG
  ↓
形成可复用的 Retriever / Filter / Renderer 模式
  ↓
再迁移到 Agent Memory / User Memory 检索
```

### 2.2 不拍脑袋设置相似度阈值

相似度阈值不能直接写死。不同 embedding 模型、距离算法、分块策略都会影响分数分布。

当前项目使用 `bge-m3` 和 pgvector `<->` 距离排序。这个距离值的合理阈值必须通过实验确定。

因此 RAG 优化顺序应该是：

```text
先记录 score / distance
  ↓
人工构造测试集
  ↓
观察相关和不相关样本的分数分布
  ↓
再选择初始阈值
  ↓
配置化上线
```

### 2.3 召回内容必须可观测

RAG 不能只返回一段拼接文本。每次工具调用至少应该能观察到：

- 检索 query。
- 命中的知识库 ID。
- 命中的 documentId / documentTitle。
- 命中的 chunkId。
- chunk 内容摘要。
- distance / score。
- 是否被过滤。
- 最终注入给模型的片段。

没有这些信息，就无法调试召回效果，也无法为后续阈值、rerank 和记忆检索做实验。

### 2.4 多格式导入统一转 Markdown

LLM 和后续分块更适合消费结构化文本。多格式支持不应该为每种格式单独写一套分块逻辑，而应该统一为：

```text
PDF / DOCX / PPTX / XLSX / HTML / CSV / JSON / XML
  ↓
转换为 Markdown
  ↓
走统一 Markdown 清洗、分块、embedding 流程
```

Microsoft MarkItDown 正好适合作为这个转换层。它是面向 LLM 和文本分析场景的轻量文档转 Markdown 工具，支持 PDF、PowerPoint、Word、Excel、图片、音频、HTML、CSV、JSON、XML、ZIP、YouTube、EPub 等格式。

参考：

- https://github.com/microsoft/markitdown

## 3. 当前实现现状

### 3.1 文档处理

当前文档处理核心在 `DocumentFacadeServiceImpl.processMarkdownDocument()`。

现状：

```text
读取上传后的文件
  ↓
MarkdownParserService.parseMarkdown()
  ↓
按 Markdown section 生成 chunk
  ↓
对 section title 生成 embedding
  ↓
chunk_bge_m3 入库
```

主要问题：

- 上传入口实际只适合 Markdown。
- 非 Markdown 文件无法进入统一处理流程。
- 当前 embedding 主要基于 section title，而不是更完整的 chunk 表达。
- chunk metadata 未充分保存标题、层级、来源、页码等信息。

### 3.2 检索处理

当前检索核心在 `RagServiceImpl.similaritySearch()`。

现状：

```text
query
  ↓
bge-m3 embedding
  ↓
pgvector ORDER BY embedding <-> queryEmbedding
  ↓
LIMIT 3
  ↓
返回 chunk.content 列表
```

主要问题：

- 只返回文本，不返回 score / distance。
- 固定 top3，不可配置。
- 没有相似度阈值。
- 没有 rerank。
- 没有去重。
- 没有召回调试信息。
- 没有“不足以回答”的明确返回。

### 3.3 KnowledgeTool

当前 `KnowledgeTool` 参数：

```text
kbsId: 知识库 ID
query: 查询文本
```

返回：

```text
String.join("\n", chunks)
```

主要问题：

- 工具返回缺少结构化信息。
- Agent 无法知道每段内容来自哪个文档。
- 前端也无法展示命中来源、相关分数和引用。

## 4. 目标架构

升级后的 RAG 链路：

```text
文件上传
  ↓
DocumentConverter
  ├── Markdown / TXT：直接读取
  └── PDF / DOCX / PPTX / XLSX / HTML / CSV / JSON / XML：MarkItDown CLI 转 Markdown
  ↓
MarkdownNormalizer
  ↓
Chunker
  ↓
EmbeddingService
  ↓
VectorStore(pgvector)
  ↓
KnowledgeTool
  ↓
Retriever
  ↓
RelevanceFilter
  ↓
Reranker（后续）
  ↓
ContextRenderer
  ↓
Agent 最终回答
```

建议拆分模块：

```text
com.kama.jchatmind.rag
  ├── DocumentConversionService
  ├── MarkdownDocumentConverter
  ├── MarkItDownCliDocumentConverter
  ├── RagIngestionService
  ├── RagRetrievalService
  ├── RagRelevanceFilter
  ├── RagContextRenderer
  └── RagDebugRecorder
```

## 5. 多格式文档导入方案

### 5.1 采用方案 A：Java 后端调用 MarkItDown CLI

当前阶段采用 CLI 适配器，而不是直接把 Python 包嵌入 Java 主服务。

原因：

- 改动范围小。
- 可以快速复用 MarkItDown 的格式支持。
- 不影响现有 Markdown 入库流程。
- 后续可以平滑替换成独立 document-converter 服务。

目标流程：

```text
用户上传文件
  ↓
后端保存原始文件
  ↓
根据扩展名判断处理方式
  ↓
.md / .txt：直接读取
其他白名单格式：调用 markitdown CLI
  ↓
得到 markdownText
  ↓
统一进入 Markdown 清洗、分块、embedding
```

### 5.2 支持格式

MVP 建议先支持：

```text
.md
.txt
.pdf
.docx
.pptx
.xlsx
.html
.csv
.json
.xml
```

暂不建议第一版支持：

- 音频转写。
- YouTube URL。
- ZIP 递归处理。
- 任意远程 URL。
- 需要外部云服务的高级 OCR。

原因是这些能力会引入更复杂的安全、成本和错误处理问题。

### 5.3 CLI 调用方式

MarkItDown CLI 示例：

```bash
markitdown input.pdf -o output.md
```

后端封装建议：

```text
MarkItDownCliDocumentConverter
  - 检查 markitdown 是否可用
  - 构造临时输出路径
  - ProcessBuilder 调用 CLI
  - 设置超时时间
  - 捕获 stdout / stderr
  - 返回 markdownText
```

配置示例：

```yaml
rag:
  document-converter:
    markitdown-command: markitdown
    timeout-seconds: 60
    max-file-size-mb: 30
    enabled-formats:
      - md
      - txt
      - pdf
      - docx
      - pptx
      - xlsx
      - html
      - csv
      - json
      - xml
```

### 5.4 安全约束

MarkItDown 官方提醒：它会以当前进程权限进行 I/O，因此服务端场景必须限制输入。

本项目应遵守：

- 只允许转换用户上传后保存到本地临时目录的文件。
- 禁止把用户输入的任意路径传给 CLI。
- 禁止第一阶段支持远程 URL 转换。
- 限制文件大小。
- 限制转换超时。
- 转换失败时返回清晰错误，不影响后端进程。
- 原始文件和转换后 Markdown 都应保存可追踪路径。

### 5.5 图片、扫描件和嵌入图片处理

MarkItDown 支持图片相关输入，但图片内容处理需要分层看待。

MVP 基础版：

```text
文本型 PDF / Word / PPT / Excel
  ↓
优先提取文档中的文本、标题、表格、列表等结构

图片文件 / 文档中的嵌入图片
  ↓
可能只能提取有限 metadata，不能保证理解图片里的文字或图形内容
```

因此第一版应在产品和文档中明确提示：

```text
如果文档关键信息位于截图、扫描件、图片表格、流程图中，基础版转换可能无法完整解析。
```

增强版可以再引入：

- `markitdown-ocr` 插件，对 PDF、DOCX、PPTX、XLSX 中的嵌入图片做 OCR。
- Vision 模型，为图片、流程图、截图生成文字描述。
- Azure Document Intelligence / Content Understanding 等云服务，处理复杂版式、扫描件和表格。

建议分阶段处理：

```text
Phase 1：只承诺文本型文档转换，不保证图片内容解析
Phase 2：接 OCR，提取图片中的文字
Phase 3：接 Vision，生成图像语义描述并进入 Markdown / embedding 流程
```

## 6. RAG 召回质量优化方案

### 6.1 第一阶段：召回可观测

第一阶段不要急着设置硬阈值，先把召回结果变得可观测。

需要改造返回结构：

```java
public class RagSearchResult {
    private String chunkId;
    private String documentId;
    private String documentTitle;
    private String content;
    private String metadata;
    private Double distance;
    private Double score;
    private Boolean filtered;
    private String filterReason;
}
```

KnowledgeTool 返回内容建议改为结构化文本：

```text
检索问题：xxx

命中的知识片段：
[1] 文档：xxx
chunkId：xxx
distance：0.4123
内容：
...

[2] 文档：yyy
chunkId：yyy
distance：0.5301
内容：
...

使用规则：
- 只能基于以上片段回答。
- 如果片段不足以回答，请说明知识库没有找到足够相关的信息。
- 不要编造片段中没有的内容。
```

同时日志记录：

```text
kbId
query
rawTopK
finalTopK
chunkId
documentId
distance
filtered
filterReason
```

### 6.2 第二阶段：实验确定阈值

阈值不能拍脑袋确定。

实验流程：

```text
准备 20-50 个测试问题
  ↓
每个问题记录 topK 召回结果和 distance
  ↓
人工标注每个 chunk 是否相关
  ↓
观察相关 / 不相关样本的 distance 分布
  ↓
选择初始 max-distance
  ↓
上线为配置项
```

需要关注指标：

- top1 命中率。
- top3 命中率。
- 相关 chunk 的 distance 分布。
- 不相关 chunk 的 distance 分布。
- 阈值提高后的漏召回比例。
- 阈值降低后的噪声比例。

配置建议：

```yaml
rag:
  retrieval:
    raw-top-k: 10
    final-top-k: 3
    max-distance:
    debug-enabled: true
```

初期 `max-distance` 可以为空，只记录不拦截。实验后再启用。

### 6.3 第三阶段：基础过滤

在有实验数据后启用基础过滤：

```text
向量召回 rawTopK
  ↓
distance 过滤
  ↓
空内容过滤
  ↓
重复内容过滤
  ↓
同文档过多片段限制
  ↓
finalTopK
```

如果过滤后没有可用结果，KnowledgeTool 应返回：

```text
知识库中没有找到与当前问题足够相关的内容。
请不要基于知识库编造答案，可以基于通用知识回答并说明未找到知识库依据。
```

### 6.4 第四阶段：Rerank

当基础过滤仍然不稳定时，再引入 rerank。

推荐流程：

```text
向量召回 top20
  ↓
reranker 根据 query + chunk 重新排序
  ↓
取 top3
```

可选方案：

- 使用本地 bge reranker 模型。
- 使用外部 rerank API。
- 使用 LLM 做轻量 relevance check。

MVP 不建议第一版就引入 rerank。先完成可观测和实验，否则 rerank 效果也无法判断。

### 6.5 第五阶段：答案引用和忠实性约束

最终回答应尽量可追溯：

```text
回答内容...

依据：
[1] 文档 A - 章节 xxx
[2] 文档 B - 章节 yyy
```

Prompt 约束：

```text
如果知识片段不足以回答问题，必须明确说明“知识库中没有足够信息”。
不要把通用知识伪装成知识库内容。
如果使用通用知识补充，必须明确区分。
```

## 7. 分块策略优化

当前分块主要依赖 Markdown section。

后续建议：

### 7.1 保存更完整 metadata

每个 chunk 至少保存：

```json
{
  "documentTitle": "xxx",
  "sectionTitle": "xxx",
  "headingPath": ["一级标题", "二级标题"],
  "sourceFileType": "pdf",
  "originalFileName": "xxx.pdf",
  "chunkIndex": 3
}
```

如果 MarkItDown 输出包含页码或结构注释，也应尽量保留。

### 7.2 chunk embedding 内容

当前对 section title 做 embedding。后续建议改成：

```text
embeddingText = headingPath + title + contentSummary + content
```

至少应包含标题和正文关键内容，而不是只 embedding 标题。

### 7.3 chunk 大小

建议配置化：

```yaml
rag:
  chunking:
    max-chars: 1200
    overlap-chars: 150
```

不要一开始追求最优值，先记录召回效果，再调整。

## 8. 与 Agent 工具调用的关系

KnowledgeTool 应继续作为 Agent 工具存在，但工具描述需要更明确：

```text
工具名：KnowledgeTool
作用：从当前 Agent 绑定的知识库中检索与用户问题相关的知识片段。
参数：
- kbsId：知识库 UUID，必须来自当前 Agent 可用知识库列表。
- query：检索问题，应使用用户问题的核心语义。
返回：
- 命中的知识片段
- 来源文档
- 相关分数
- 使用规则
限制：
- 如果用户没有询问知识库相关内容，不要调用。
- 如果返回结果不足以回答，不要编造。
```

后端仍然必须校验：

- `kbsId` 是合法 UUID。
- `kbsId` 属于当前 Agent 绑定知识库。
- 空 query 直接拒绝。

## 9. 分阶段实施路线

### Phase 1：MarkItDown 多格式导入 MVP

目标：

- 支持常见文档格式上传。
- 非 Markdown 文件先转换为 Markdown，再复用现有入库流程。

任务：

- 增加 `DocumentConversionService`。
- 增加 `MarkItDownCliDocumentConverter`。
- 配置 MarkItDown CLI 路径、超时、文件大小和格式白名单。
- 修改文档上传处理流程。
- 保存原始文件类型和转换后 Markdown metadata。

验收：

- `.md` 文件原流程不受影响。
- `.pdf / .docx / .pptx / .xlsx` 可以成功上传并入库。
- MarkItDown 未安装时有清晰错误提示。
- 超大文件或不支持格式会被拒绝。

### Phase 2：RAG 召回可观测

目标：

- 每次检索都能看到命中了什么、分数是多少、最终注入了什么。

任务：

- 修改 mapper 查询，返回 `distance`。
- 新增 `RagSearchResult`。
- 修改 `RagService` 返回结构化结果。
- 修改 `KnowledgeTool` 返回带来源和分数的结构化文本。
- 增加检索日志。

验收：

- 能看到每次 query 的 topK chunk。
- 能看到 chunkId、docId、distance。
- 前端或日志能辅助调试召回质量。

### Phase 3：阈值实验和基础过滤

目标：

- 根据实验数据确定初始阈值。
- 过滤明显不相关结果。

任务：

- 准备 RAG 测试集。
- 人工标注召回相关性。
- 确定 `max-distance` 初始值。
- 增加可配置 `rawTopK / finalTopK / maxDistance`。
- 增加空结果处理。

验收：

- 不相关 query 不再强行返回低质量 chunk。
- 相关 query 仍能召回足够信息。
- 阈值可通过配置调整。

### Phase 4：Rerank

目标：

- 提高召回排序质量。

任务：

- 向量召回扩大到 top20。
- 接入 reranker。
- 输出 rerankScore。
- 对比 rerank 前后命中率。

验收：

- top3 相关性优于纯向量检索。
- reranker 异常时可降级为基础向量检索。

### Phase 5：答案引用和前端展示

目标：

- 用户能看到回答依据。

任务：

- KnowledgeTool 返回 citation 信息。
- Agent prompt 要求回答带引用。
- 前端展示命中文档、片段和分数。

验收：

- RAG 回答能追溯到具体文档片段。
- 知识库不足时不会编造。

## 10. 和记忆系统升级的关系

RAG 优化完成后，可以将同一套模式迁移到记忆检索：

```text
Knowledge RAG
  - document_chunk
  - kb_id
  - document metadata

Memory Retrieved
  - agent_memory / user_memory
  - agent_id / user_id
  - memory_type / memory_scope
```

可复用能力：

- embedding 生成。
- topK 检索。
- distance 记录。
- 阈值实验。
- relevance filter。
- context renderer。
- debug 信息。

区别：

- Knowledge RAG 召回错了，主要影响知识回答。
- Memory 召回错了，可能影响 Agent 身份和长期上下文。

因此必须先在知识库 RAG 上验证召回质量，再迁移到记忆系统。

## 11. 面试表达

可以这样概括：

> 我们最初的 RAG 只是 Markdown 文档上传后做向量检索，能力比较基础。后续我设计了两条升级线：第一是文档摄取层，引入 Microsoft MarkItDown，把 PDF、Word、PPT、Excel 等常见格式统一转换成 Markdown，再复用原有分块和 embedding 流程；第二是检索质量层，不直接拍脑袋设置相似度阈值，而是先让检索结果可观测，记录 chunk、来源文档和 distance，再通过测试集标注相关性来确定阈值，后续再引入 rerank、引用和答案忠实性约束。这样 RAG 不再只是“向量 topK 拼 prompt”，而是一个可调试、可评估、可逐步优化的检索增强系统。
