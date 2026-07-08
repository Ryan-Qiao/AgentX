# BGE Reranker v2 M3 离线评测报告

## 1. 评测目标

验证 `BAAI/bge-reranker-v2-m3` 是否优于当前轻量级 Hybrid Rerank。

当前线上轻量级方案：

```text
rerankScore = 0.70 * vectorScore + 0.30 * lexicalScore
```

本次新增实验：

```text
bge-reranker-v2-m3(query, chunk) -> rerankScore
```

## 2. 评测口径

- 输入分块：`docs/rag-evaluation/mysql-pdf-chunking/mysql-xiaolin-chunks.jsonl`
- chunk 数：177
- 问题数：220
- Embedding 模型：`bge-m3`
- Reranker 模型：`BAAI/bge-reranker-v2-m3`
- rawTopK：20
- finalTopK：3
- chunk 策略：PDF plain text chunking，`maxChars=1200`，`overlapChars=150`

三组对比：

| 组别 | 策略 |
| --- | --- |
| Vector | 纯向量距离排序 |
| Hybrid | 向量粗召回后，`0.70 * vectorScore + 0.30 * lexicalScore` 重排 |
| BGE Reranker | 向量粗召回后，使用 `bge-reranker-v2-m3` 对 query/chunk pair 重排 |

## 3. Top3 检索指标

| 指标 | Vector | Hybrid | BGE Reranker | BGE 相比 Hybrid |
| --- | ---: | ---: | ---: | ---: |
| HitRate@1 | 46.82% | 86.82% | 79.55% | -7.27% |
| Recall@3 | 70.00% | 93.18% | 90.00% | -3.18% |
| Precision@3 | 25.76% | 36.36% | 34.85% | -1.52% |
| MRR | 56.97% | 89.85% | 84.24% | -5.61% |

## 4. 按当前入 Prompt 策略后的指标

当前生产策略还会套一层 `maxChunksPerDocument=2`。因为本次只测一个 PDF，所以实际最多注入 2 个 chunk。

| 指标 | Vector + Policy | Hybrid + Policy | BGE + Policy | BGE 相比 Hybrid |
| --- | ---: | ---: | ---: | ---: |
| HitRate@1 | 46.82% | 86.82% | 79.55% | -7.27% |
| Recall@3 | 61.36% | 92.27% | 86.82% | -5.45% |
| Precision@3 | 22.27% | 35.15% | 33.03% | -2.12% |
| MRR | 54.09% | 89.55% | 83.18% | -6.36% |

## 5. BGE Reranker 分数分布

正样本表示命中标准答案所在 chunk，负样本表示未命中标准答案所在 chunk。

| 样本 | count | min | p10 | p25 | p50 | p75 | p90 | max | mean |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 正样本 | 230 | 0.7023 | 2.3372 | 3.0540 | 3.9271 | 4.8181 | 5.6658 | 7.6220 | 3.9663 |
| 负样本 | 430 | -4.5440 | -1.0232 | 0.3985 | 1.7438 | 2.8289 | 4.0089 | 6.5910 | 1.6244 |

## 6. 初步结论

- BGE Reranker 相比当前 Hybrid 的 HitRate@1 变化：-7.27%。
- BGE Reranker 相比当前 Hybrid 的 Recall@3 变化：-3.18%。
- 在这套偏原文线索型的评测集中，BGE Reranker 没有超过当前 Hybrid Rerank。
- 当前不建议直接用 `bge-reranker-v2-m3` 替换线上 Hybrid Rerank；更合理的下一步是补充更接近真实用户问法的改写型/总结型问题，再评估模型 reranker 是否能发挥优势。

## 7. 产物

- 数据集：`docs/rag-evaluation/bge-reranker-comparison/mysql-bge-reranker-dataset.jsonl`
- 逐题结果：`docs/rag-evaluation/bge-reranker-comparison/mysql-bge-reranker-results.jsonl`
- 汇总指标：`docs/rag-evaluation/bge-reranker-comparison/mysql-bge-reranker-summary.json`
- 评测脚本：`scripts/rag_bge_reranker_evaluation.py`

## 8. 局限

- 本评测仍是检索层评测，不等价于最终大模型回答质量。
- 问题由原文自动构造，比真实用户问题更贴近原文。
- BGE Reranker 的分数尺度和当前 Hybrid 分数不同，阈值需要重新基于正负样本分布确定。
