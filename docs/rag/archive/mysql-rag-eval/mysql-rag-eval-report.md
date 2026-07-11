# JChatMind RAG PDF 准确率评测报告

## 1. 测试对象

- 参考 PDF：`/Users/ryan/Downloads/图解MySQL-小林coding-亮白版-v2.0.pdf`
- PDF 有文本页数：329
- 评测问题数：220
- 文档 chunk 数：225
- Embedding 模型：`bge-m3`
- 召回流程：PDF 文本分块 -> bge-m3 embedding -> L2 distance rawTopK -> hybrid rerank -> finalTopK

## 2. 数据集构造方法

本评测从 PDF 中逐页抽取文本，将文本切分为约 900 字符的 chunk，并保留页码范围。

问题集自动从 PDF 句子中构造，每条样本包含：

- `question`：基于 PDF 句子和关键词生成的问题。
- `answer`：PDF 中的原文答案句。
- `answer_page`：答案所在页。
- `relevant_chunk_id`：答案所在 chunk。
- `answer_source`：用于自动判定召回命中的答案原文。

为了避免答案脱离 PDF，所有标准答案都直接来自 PDF 原文句子。

## 3. 判定口径

本次评测关注 RAG 检索层，而不是大模型生成层。

- `HitRate@1`：Top1 chunk 是否包含标准答案原文。
- `Recall@3`：Top3 chunk 中是否至少有一个包含标准答案原文。
- `Precision@3`：Top3 中包含标准答案原文的比例。
- `MRR`：第一个命中答案 chunk 的倒数排名。
- `mean_top1_distance`：Top1 chunk 的平均 L2 distance。

如果 Top3 中包含标准答案原文，就认为本次 RAG 已经把回答所需依据召回给模型。

## 4. RAG 配置

```text
rawTopK = 10
finalTopK = 3
vectorWeight = 0.7
lexicalWeight = 0.3
```

## 5. 测试结果

| 指标 | 纯向量排序 | Hybrid Rerank 后 | 变化 |
| --- | ---: | ---: | ---: |
| hit_rate_at_1 | 0.4409 | 0.8364 | +0.3955 |
| hit_rate_at_3 | 0.6818 | 0.8818 | +0.2000 |
| recall_at_3 | 0.6818 | 0.8818 | +0.2000 |
| precision_at_3 | 0.2576 | 0.3455 | +0.0879 |
| mrr | 0.5447 | 0.8583 | +0.3136 |

补充：

```text
mean_top1_distance = 21.2687
```

## 6. 产物

- 数据集：`docs/rag-evaluation/mysql-rag-eval-dataset.jsonl`
- 逐题结果：`docs/rag-evaluation/mysql-rag-eval-results.jsonl`
- 汇总指标：`docs/rag-evaluation/mysql-rag-eval-summary.json`
- 评测脚本：`scripts/rag_pdf_evaluation.py`

## 7. 结论

本次测试集超过 200 题，所有答案均来自 PDF 原文。

当前 RAG 在该 PDF 上的主要指标为：

- HitRate@1：83.64%
- Recall@3：88.18%
- Precision@3：34.55%
- MRR：85.83%

这些指标说明：在每个问题只要求召回答案所在 chunk 的口径下，当前 RAG 检索链路可以较稳定地把 PDF 中的答案依据召回给模型。

和纯向量排序相比，Hybrid Rerank 的变化为：

- HitRate@1：+39.55%
- Recall@3：+20.00%
- Precision@3：+8.79%
- MRR：+31.36%

## 8. 局限

- 本评测使用答案原文包含关系自动判定，适合检索层回归，不等价于最终大模型回答质量。
- PDF 抽取文本可能受版式、图片、表格影响；如果答案只存在于图片中，本测试无法覆盖。
- 问题由 PDF 原文句子自动构造，覆盖面大但表达方式偏直接；真实用户问题可能更口语化，需要另建人工 query 集补充。
