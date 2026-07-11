# Markdown 自然问题 RAG 评测报告

## 1. 评测目标

基于原生 Markdown 文档 `/Users/ryan/Downloads/前言.md` 构造一套更接近真实用户问法的 RAG 检索测试集，对比三种检索策略：

| 方法 | 说明 |
| --- | --- |
| Vector | 纯向量相似度排序 |
| Hybrid | 向量粗召回 rawTopK 后，用 `0.70 * vectorScore + 0.30 * lexicalScore` 重排 |
| BGE Reranker | 向量粗召回 rawTopK 后，用 `bge-reranker-v2-m3` 对 query/chunk pair 重排 |

## 2. 数据构造

- 原始文档：`/Users/ryan/Downloads/前言.md`
- chunk 数：456
- 测试问题数：30
- 分块策略：使用 Python 脚本模拟业务代码 `MarkdownChunkingServiceImpl` 的 Markdown heading section chunking 逻辑；核心参数与业务配置保持一致，`maxChars=1200`、`overlapChars=150`。脚本通过识别 fenced code block 避免将代码块内 `#` 注释误判为标题，但本次评测没有直接调用 Java 业务分块服务。
- 问题构造：先从目标 chunk 中固定 `answer_source` 作为标准证据，再让模型改写为自然用户问题和参考答案。
- 校验规则：问题不能出现“根据文档/原文/这段内容”等测试痕迹，且不能连续复用证据文本超过 10 个中文字符。

示例问题：

- Q：学习LangChain和LangGraph需要什么版本的Python？
  - evidence：Python版本：3.10及以上（推荐3.10，兼容性最好）；...
- Q：为什么我用LangChain调用模型时总是报错'未检测到API_KEY'？
  - evidence：如果报错，常见原因：① `.env` 文件不在项目根目录下；② API_KEY 填写有误或余额不足；③ BASE_URL 不匹配。...
- Q：LangChain 中如何实现多轮对话的记忆功能？
  - evidence：holder(variable_name="chat_history")**
这是一个历史消息占位符。这是实现对话记忆（记忆功能）的关键。它不在模板中写死任何内容，而是在程序运行时，动态地将之前的对话记录（比如用户之前问了什么，AI回答了什...
- Q：如何用LangChain实现一个只保留最近两轮对话的聊天机器人？
  - evidence：WINDOW_SIZE = 2 # 保留最近2轮对话（即最近4条消息：用户-助手-用户-助手）...
- Q：窗口记忆在对话中是如何工作的？比如如果我只保留最近两轮对话，用户问一个更早的问题会怎样？
  - evidence：窗口记忆仅保留最近 2 轮对话。第 5 轮询问时，第 3 轮"来自上海"仍在窗口内，所以 AI 能正确回答；而第 6 轮询问"我叫什么名字"时，第 1 轮"我叫小红"已被截断，AI 将无法回答，这就是窗口记忆的截断效果。...

## 3. 检索配置

```text
embeddingModel = bge-m3
rerankerModel = BAAI/bge-reranker-v2-m3
rawTopK = 20
finalTopK = 3
vectorWeight = 0.7
lexicalWeight = 0.3
```

## 4. Top3 指标

| 指标 | Vector | Hybrid | BGE Reranker | BGE 相比 Hybrid |
| --- | ---: | ---: | ---: | ---: |
| HitRate@1 | 43.33% | 43.33% | 46.67% | +3.33% |
| Recall@3 | 66.67% | 70.00% | 76.67% | +6.67% |
| Precision@3 | 23.33% | 25.56% | 27.78% | +2.22% |
| MRR | 54.44% | 55.56% | 60.56% | +5.00% |

## 5. BGE Reranker 分数分布

| 样本 | count | min | p10 | p25 | p50 | p75 | p90 | max | mean |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 正样本 | 25 | -0.7604 | 1.6141 | 3.1315 | 4.5468 | 6.5902 | 6.9051 | 7.9885 | 4.5237 |
| 负样本 | 65 | -2.9385 | 1.2108 | 2.4826 | 3.4955 | 4.5039 | 5.2320 | 7.9524 | 3.3620 |

## 6. 结论

- 在这套自然问题测试集上，BGE Reranker 相比 Hybrid 的 HitRate@1 变化：+3.33%。
- BGE Reranker 相比 Hybrid 的 Recall@3 变化：+6.67%。
- 这套数据比之前“原文线索型”问题更接近真实用户表达，因此更适合判断 reranker 是否能处理改写、场景化和面试表达类问题。

## 7. 产物

- Chunks：`docs/rag-evaluation/easy-langent-natural/easy-langent-chunks.jsonl`
- 测试集：`docs/rag-evaluation/easy-langent-natural/easy-langent-natural-dataset.jsonl`
- 逐题结果：`docs/rag-evaluation/easy-langent-natural/easy-langent-natural-results.jsonl`
- 汇总指标：`docs/rag-evaluation/easy-langent-natural/easy-langent-natural-summary.json`
- 评测脚本：`scripts/rag_markdown_natural_eval.py`
