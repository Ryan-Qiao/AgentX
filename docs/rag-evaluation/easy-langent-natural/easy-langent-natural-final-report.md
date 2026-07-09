# Easy Langent 自然问题 RAG 三方案评测报告

## 1. 评测目标

验证新的自然语言问题测试集是否能正常用于 RAG 检索评测，并对比三种检索方案：

| 方案 | 说明 |
| --- | --- |
| Vector | 使用 `bge-m3` 对 query 和 chunk embedding，按 L2 distance 排序 |
| Hybrid | 先取向量粗召回 `rawTopK=20`，再用 `0.70 * vectorScore + 0.30 * lexicalScore` 重排 |
| BGE Reranker | 先取向量粗召回 `rawTopK=20`，再用 `BAAI/bge-reranker-v2-m3` 对 query/chunk pair 重排 |

## 2. 数据与配置

- 文档：`/Users/ryan/Downloads/前言.md`
- chunk 文件：`docs/rag-evaluation/easy-langent-natural/easy-langent-chunks.jsonl`
- chunk 数：456
- 测试集：`docs/rag-evaluation/easy-langent-natural/easy-langent-natural-dataset.jsonl`
- 问题数：272
- rawTopK：20
- finalTopK：3
- Embedding 模型：`bge-m3`
- Reranker 模型：`BAAI/bge-reranker-v2-m3`

分块说明：

- 本次评测使用 Python 脚本模拟业务代码 `MarkdownChunkingServiceImpl` 的 Markdown heading section chunking 逻辑。
- 核心参数与业务配置保持一致：`maxChars=1200`、`overlapChars=150`。
- 脚本通过识别 fenced code block，避免将代码块内的 `#` 注释误判为 Markdown 标题。
- 本次评测没有直接调用 Java 业务分块服务，因此结论应理解为“按业务分块规则模拟”的离线评测结果。

问题类型分布：

| 类型 | 数量 |
| --- | ---: |
| concept | 182 |
| scenario | 43 |
| troubleshooting | 40 |
| comparison | 7 |

## 3. Smoke Test

先随机抽取 30 条问题跑 smoke test，验证脚本、缓存、指标口径和 BGE reranker 调用链路。

| 指标 | Vector | Hybrid | BGE Reranker |
| --- | ---: | ---: | ---: |
| HitRate@1 | 43.33% | 43.33% | 46.67% |
| Recall@3 | 66.67% | 70.00% | 76.67% |
| Precision@3 | 23.33% | 25.56% | 27.78% |
| MRR | 54.44% | 55.56% | 60.56% |

Smoke test 结果正常：

- 结果行数为 30，和问题数一致。
- 三种方案均成功产出 Top3 结果。
- BGE reranker 分数缓存正常写入。
- 指标计算口径可用。

## 4. 完整评测结果

完整评测共 272 条问题，456 个 chunk。BGE reranker 新计算 4840 对 query/chunk pair；此前 smoke test 已缓存 600 对。

| 指标 | Vector | Hybrid | BGE Reranker | BGE 相比 Hybrid |
| --- | ---: | ---: | ---: | ---: |
| HitRate@1 | 55.51% | 51.47% | 61.03% | +9.56% |
| Recall@3 | 76.84% | 74.63% | 80.88% | +6.25% |
| Precision@3 | 25.98% | 25.74% | 27.70% | +1.96% |
| MRR | 65.13% | 61.83% | 70.47% | +8.64% |

## 5. 按问题类型拆分

### Comparison

| 指标 | Vector | Hybrid | BGE Reranker |
| --- | ---: | ---: | ---: |
| 样本数 | 7 | 7 | 7 |
| HitRate@1 | 57.14% | 14.29% | 85.71% |
| Recall@3 | 85.71% | 42.86% | 85.71% |
| MRR | 69.05% | 23.81% | 85.71% |

### Concept

| 指标 | Vector | Hybrid | BGE Reranker |
| --- | ---: | ---: | ---: |
| 样本数 | 182 | 182 | 182 |
| HitRate@1 | 55.49% | 48.90% | 59.89% |
| Recall@3 | 77.47% | 70.88% | 81.87% |
| MRR | 65.20% | 58.70% | 70.24% |

### Scenario

| 指标 | Vector | Hybrid | BGE Reranker |
| --- | ---: | ---: | ---: |
| 样本数 | 43 | 43 | 43 |
| HitRate@1 | 51.16% | 53.49% | 51.16% |
| Recall@3 | 67.44% | 81.40% | 74.42% |
| MRR | 58.53% | 67.05% | 62.79% |

### Troubleshooting

| 指标 | Vector | Hybrid | BGE Reranker |
| --- | ---: | ---: | ---: |
| 样本数 | 40 | 40 | 40 |
| HitRate@1 | 60.00% | 67.50% | 72.50% |
| Recall@3 | 82.50% | 90.00% | 82.50% |
| MRR | 71.25% | 77.08% | 77.08% |

## 6. 分数分布

BGE reranker Top3 结果里的正负样本分数分布：

| 样本 | count | min | p10 | p25 | p50 | p75 | p90 | max | mean |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 正样本 | 226 | -1.9924 | 2.3990 | 3.3352 | 4.6104 | 5.7191 | 6.9354 | 9.4031 | 4.5750 |
| 负样本 | 590 | -3.1515 | 0.3784 | 1.5710 | 2.7935 | 3.7925 | 5.0043 | 7.9524 | 2.6921 |

正负样本分数有明显区分，但仍有重叠，因此不能只依赖一个固定阈值替代 TopK/Rerank 排序。

## 7. 结论

1. 新的自然问题测试集可以用于后续 RAG 检索评测。
2. 在完整 272 条自然问题上，`bge-reranker-v2-m3` 整体优于当前 Hybrid Rerank。
3. 当前 Hybrid 方案在这套数据上低于纯向量，说明 `0.70 * vectorScore + 0.30 * lexicalScore` 对自然问法不一定稳定；词面匹配可能会把一些关键词相似但语义不够精确的 chunk 顶上来。
4. BGE Reranker 对 concept 和 comparison 类问题提升更明显，符合 cross-encoder 更擅长判断 query/chunk 语义相关性的预期。
5. scenario 和 troubleshooting 类问题中，Hybrid 仍有局部优势，说明关键词匹配对代码、配置、报错类问题仍然有价值。

## 8. 建议

- 不建议直接废弃 Hybrid；更合理的是保留 Hybrid 作为轻量 baseline。
- 如果追求更高检索质量，可以在当前向量粗召回后接入 BGE Reranker 作为最终排序器。
- 后续可以继续比较：
  - Vector only
  - Hybrid only
  - BGE only
  - Hybrid + BGE feature fusion
- 如果线上接入 BGE Reranker，需要单独评估延迟、并发、缓存和部署成本。

## 9. 产物

- Smoke test 目录：`docs/rag-evaluation/easy-langent-natural-smoke/`
- 完整评测报告：`docs/rag-evaluation/easy-langent-natural/easy-langent-natural-report.md`
- 阶段总结报告：`docs/rag-evaluation/easy-langent-natural/easy-langent-natural-final-report.md`
- 完整逐题结果：`docs/rag-evaluation/easy-langent-natural/easy-langent-natural-results.jsonl`
- 完整汇总指标：`docs/rag-evaluation/easy-langent-natural/easy-langent-natural-summary.json`
- 评测脚本：`scripts/rag_markdown_natural_eval.py`
