# 当前分块策略下的 RAG 优化前后对比

## 1. 评测口径

- 输入分块：`docs/rag-evaluation/mysql-pdf-chunking/mysql-xiaolin-chunks.jsonl`
- 分块来源：`图解MySQL-小林coding-亮白版-v2.0.pdf` 经当前正式分块策略得到的 JSONL
- 文档 chunk 数：177
- 评测问题数：220
- Embedding 模型：`bge-m3`

本报告固定同一批生产分块，只比较检索策略：

- 优化前：纯向量相似度排序，直接取 Top3
- 优化后：先按向量召回 rawTopK=10，再用 `0.70 * vectorScore + 0.30 * lexicalScore` 重排，取 Top3

问题和标准答案都从当前 chunk 原文中自动构造；如果召回 chunk 包含标准答案原文，就认为检索命中。

## 2. Top3 检索能力对比

| 指标 | 优化前：纯向量 | 优化后：Hybrid Rerank | 提升 |
| --- | ---: | ---: | ---: |
| 准确率 / HitRate@1 | 46.82% | 81.36% | +34.55% |
| 召回率 / Recall@3 | 70.00% | 85.91% | +15.91% |
| 精确率 / Precision@3 | 25.76% | 33.18% | +7.42% |
| MRR | 56.97% | 83.48% | +26.52% |

## 3. 按当前默认入 Prompt 策略后的结果

当前代码默认 `maxChunksPerDocument=2`。因为这次只测一个 PDF，所以最终最多会选入 2 个 chunk；下面这组更接近当前线上配置真正塞进 prompt 的结果。

| 指标 | 优化前：纯向量 + policy | 优化后：Hybrid + policy | 提升 |
| --- | ---: | ---: | ---: |
| 准确率 / HitRate@1 | 46.82% | 81.36% | +34.55% |
| 召回率 / Recall@3 | 61.36% | 85.00% | +23.64% |
| 精确率 / Precision@3 | 22.27% | 32.12% | +9.85% |
| MRR | 54.09% | 83.18% | +29.09% |

## 4. 关键结论

- 在固定当前 177 个正式 chunk 的前提下，Hybrid Rerank 将 Top1 准确率从 46.82% 提升到 81.36%，提升 +34.55%。
- Top3 召回率从 70.00% 提升到 85.91%，提升 +15.91%。
- 因为每个问题通常只有一个标准答案 chunk，Precision@3 的理论上限接近 `1/3`；它更适合作为排序质量的辅助指标，核心看 HitRate@1、Recall@3 和 MRR。

## 5. 产物

- 数据集：`docs/rag-evaluation/current-chunking-comparison/mysql-current-chunking-dataset.jsonl`
- 逐题结果：`docs/rag-evaluation/current-chunking-comparison/mysql-current-chunking-results.jsonl`
- 汇总指标：`docs/rag-evaluation/current-chunking-comparison/mysql-current-chunking-summary.json`
- 评测脚本：`scripts/rag_current_chunking_comparison.py`

## 6. 局限

- 这是检索层评测，不等价于最终大模型回答质量。
- 问题由原文自动构造，覆盖面较广，但比真实用户问题更贴近原文。
- PDF 转 Markdown 的文本质量仍受 PDF 版式、图片和换行影响。
