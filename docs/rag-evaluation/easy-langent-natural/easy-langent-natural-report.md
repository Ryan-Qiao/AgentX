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
- 测试问题数：272
- 分块策略：使用 Python 脚本模拟业务代码 `MarkdownChunkingServiceImpl` 的 Markdown heading section chunking 逻辑；核心参数与业务配置保持一致，`maxChars=1200`、`overlapChars=150`。脚本通过识别 fenced code block 避免将代码块内 `#` 注释误判为标题，但本次评测没有直接调用 Java 业务分块服务。
- 问题构造：先从目标 chunk 中固定 `answer_source` 作为标准证据，再让模型改写为自然用户问题和参考答案。
- 校验规则：问题不能出现“根据文档/原文/这段内容”等测试痕迹，且不能连续复用证据文本超过 10 个中文字符。

示例问题：

- Q：学习LangChain和LangGraph时，初学者常遇到哪些困难？
  - evidence：多数初学者在接触智能体开发时，常陷入“框架概念繁杂、实操无从下手、技术与应用脱节”的困境——要么被复杂的理论体系吓退，要么掌握了框架基础却不知如何落地真实项目。...
- Q：easy-langent项目的学习大纲设计原则是什么？
  - evidence：本项目配套的学习大纲遵循“循序渐进、实践导向”的设计原则，从框架基础认知入手，逐步深入核心组件实操、进阶应用开发，再到多智能体协作与系统优化，最终通过综合实战完成知识闭环。...
- Q：学习智能体开发需要哪些前置知识？
  - evidence：熟悉Python编程语言基础
- 对大模型技术有基本了解
- 对智能体的核心概念有基本了解...
- Q：运行LangChain和LangGraph项目时，推荐的Python版本和框架版本是什么？
  - evidence：为确保项目顺利运行，建议使用以下版本的开发环境（Lang系列框架必须使用v1.0.0及以上版本）
- Python: 3.11 或更高版本
- LangChain: 1.0.0 或更高版本
- LangGraph: 1.0.0 或更高版本...
- Q：学习LangChain和LangGraph时，应该按照什么顺序来学效果最好？
  - evidence：请严格遵循“框架认知→组件实操→进阶应用→综合实战”的学习顺序。...

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
| HitRate@1 | 55.51% | 51.47% | 61.03% | +9.56% |
| Recall@3 | 76.84% | 74.63% | 80.88% | +6.25% |
| Precision@3 | 25.98% | 25.74% | 27.70% | +1.96% |
| MRR | 65.13% | 61.83% | 70.47% | +8.64% |

## 5. BGE Reranker 分数分布

| 样本 | count | min | p10 | p25 | p50 | p75 | p90 | max | mean |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 正样本 | 226 | -1.9924 | 2.3990 | 3.3352 | 4.6104 | 5.7191 | 6.9354 | 9.4031 | 4.5750 |
| 负样本 | 590 | -3.1515 | 0.3784 | 1.5710 | 2.7935 | 3.7925 | 5.0043 | 7.9524 | 2.6921 |

## 6. 结论

- 在这套自然问题测试集上，BGE Reranker 相比 Hybrid 的 HitRate@1 变化：+9.56%。
- BGE Reranker 相比 Hybrid 的 Recall@3 变化：+6.25%。
- 这套数据比之前“原文线索型”问题更接近真实用户表达，因此更适合判断 reranker 是否能处理改写、场景化和面试表达类问题。

## 7. 产物

- Chunks：`docs/rag-evaluation/easy-langent-natural/easy-langent-chunks.jsonl`
- 测试集：`docs/rag-evaluation/easy-langent-natural/easy-langent-natural-dataset.jsonl`
- 逐题结果：`docs/rag-evaluation/easy-langent-natural/easy-langent-natural-results.jsonl`
- 汇总指标：`docs/rag-evaluation/easy-langent-natural/easy-langent-natural-summary.json`
- 评测脚本：`scripts/rag_markdown_natural_eval.py`
