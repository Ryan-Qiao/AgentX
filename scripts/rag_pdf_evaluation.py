#!/usr/bin/env python3
"""Build and evaluate a PDF-grounded RAG benchmark for JChatMind."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import statistics
import time
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Iterable
from urllib import request

import pdfplumber


DEFAULT_RAW_TOP_K = 10
DEFAULT_FINAL_TOP_K = 3
DEFAULT_MAX_CHUNKS_PER_DOCUMENT = 2
DEFAULT_VECTOR_WEIGHT = 0.70
DEFAULT_LEXICAL_WEIGHT = 0.30
QUESTION_COUNT = 220

MYSQL_TERMS = [
    "MySQL",
    "SQL",
    "索引",
    "B+",
    "事务",
    "隔离",
    "锁",
    "日志",
    "redo",
    "undo",
    "binlog",
    "MVCC",
    "Buffer Pool",
    "InnoDB",
    "主键",
    "回表",
    "覆盖索引",
    "联合索引",
    "幻读",
    "死锁",
    "查询",
    "优化器",
]


@dataclass
class Chunk:
    chunk_id: str
    page_start: int
    page_end: int
    content: str


@dataclass
class Question:
    id: str
    question: str
    answer: str
    answer_page: int
    relevant_chunk_id: str
    answer_source: str


@dataclass
class RetrievalHit:
    rank: int
    chunk_id: str
    page_start: int
    page_end: int
    distance: float
    score: float
    lexical_score: float
    rerank_score: float
    contains_answer: bool


def normalize_text(text: str) -> str:
    text = text.replace("\u3000", " ")
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def compact_for_match(text: str) -> str:
    return re.sub(r"\s+", "", text)


def sentence_split(text: str) -> list[str]:
    text = normalize_text(text)
    parts = re.split(r"(?<=[。！？；])", text)
    sentences = []
    for part in parts:
        sentence = normalize_text(part)
        if 35 <= len(sentence) <= 180 and any(term in sentence for term in MYSQL_TERMS):
            sentences.append(sentence)
    return sentences


def extract_pages(pdf_path: Path) -> list[dict]:
    pages = []
    with pdfplumber.open(pdf_path) as pdf:
        for index, page in enumerate(pdf.pages, start=1):
            text = normalize_text(page.extract_text() or "")
            if text:
                pages.append({"page": index, "text": text})
    return pages


def build_chunks(pages: list[dict], target_chars: int = 900, overlap_chars: int = 120) -> list[Chunk]:
    chunks: list[Chunk] = []
    buffer = ""
    page_start = None
    page_end = None

    def flush(force: bool = False) -> None:
        nonlocal buffer, page_start, page_end
        if not buffer.strip():
            return
        if not force and len(buffer) < target_chars:
            return
        content = normalize_text(buffer)
        chunk_hash = hashlib.sha1(f"{page_start}:{page_end}:{content}".encode("utf-8")).hexdigest()[:12]
        chunks.append(
            Chunk(
                chunk_id=f"chunk-{len(chunks) + 1:04d}-{chunk_hash}",
                page_start=page_start or 0,
                page_end=page_end or 0,
                content=content,
            )
        )
        overlap = content[-overlap_chars:] if overlap_chars > 0 else ""
        buffer = overlap
        page_start = page_end

    for page in pages:
        paragraphs = [normalize_text(p) for p in re.split(r"\n+", page["text"]) if normalize_text(p)]
        for paragraph in paragraphs:
            if page_start is None:
                page_start = page["page"]
            page_end = page["page"]
            buffer = f"{buffer}\n{paragraph}".strip()
            flush(force=False)
    flush(force=True)
    return chunks


def question_template(sentence: str, index: int) -> str:
    key_terms = [term for term in MYSQL_TERMS if term in sentence]
    topic = key_terms[0] if key_terms else "相关内容"
    clue = re.sub(r"\s+", "", sentence)
    clue = re.sub(r"[。！？；，,、]+", "，", clue).strip("，")
    clue = clue[:46]
    templates = [
        "根据《图解MySQL》，请说明这段线索对应的完整结论：{clue}",
        "PDF 中关于“{clue}”的原文要点是什么？",
        "请依据 PDF 回答：{topic}相关内容里，“{clue}”这句话想表达什么？",
        "文档里提到“{clue}”时，完整说法是什么？",
    ]
    return templates[index % len(templates)].format(topic=topic, clue=clue)


def build_questions(chunks: list[Chunk], count: int) -> list[Question]:
    questions: list[Question] = []
    seen_answers: set[str] = set()
    for chunk in chunks:
        for sentence in sentence_split(chunk.content):
            answer_key = compact_for_match(sentence)
            if answer_key in seen_answers:
                continue
            seen_answers.add(answer_key)
            idx = len(questions) + 1
            questions.append(
                Question(
                    id=f"mysql-rag-{idx:03d}",
                    question=question_template(sentence, idx),
                    answer=sentence,
                    answer_page=chunk.page_start,
                    relevant_chunk_id=chunk.chunk_id,
                    answer_source=sentence,
                )
            )
            if len(questions) >= count:
                return questions
    raise RuntimeError(f"Only generated {len(questions)} questions; need {count}.")


def load_embedding_cache(cache_path: Path) -> dict[str, list[float]]:
    if not cache_path.exists():
        return {}
    return json.loads(cache_path.read_text(encoding="utf-8"))


def save_embedding_cache(cache_path: Path, cache: dict[str, list[float]]) -> None:
    cache_path.parent.mkdir(parents=True, exist_ok=True)
    cache_path.write_text(json.dumps(cache, ensure_ascii=False), encoding="utf-8")


def embed(text: str, cache: dict[str, list[float]], model: str, host: str) -> list[float]:
    key = hashlib.sha256(f"{model}\0{text}".encode("utf-8")).hexdigest()
    if key in cache:
        return cache[key]
    payload = json.dumps({"model": model, "prompt": text}).encode("utf-8")
    req = request.Request(
        f"{host.rstrip('/')}/api/embeddings",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with request.urlopen(req, timeout=120) as response:
        data = json.loads(response.read().decode("utf-8"))
    embedding = data["embedding"]
    cache[key] = embedding
    return embedding


def l2_distance(left: list[float], right: list[float]) -> float:
    return math.sqrt(sum((a - b) * (a - b) for a, b in zip(left, right)))


def cjk_features(text: str) -> set[str]:
    chars = [char for char in text if "\u4e00" <= char <= "\u9fff"]
    features = set(chars)
    for i in range(len(chars) - 1):
        features.add(chars[i] + chars[i + 1])
    return features


def lexical_features(text: str) -> set[str]:
    text = text.lower()
    features = {token for token in re.findall(r"[a-zA-Z0-9]+", text) if len(token) >= 2}
    features.update(cjk_features(text))
    return features


def lexical_score(query: str, text: str) -> float:
    query_features = lexical_features(query)
    if not query_features:
        return 0.0
    text_features = lexical_features(text)
    if not text_features:
        return 0.0
    return len(query_features & text_features) / len(query_features)


def retrieve(
    question: Question,
    chunks: list[Chunk],
    chunk_embeddings: dict[str, list[float]],
    query_embedding: list[float],
    raw_top_k: int,
    final_top_k: int,
    vector_weight: float,
    lexical_weight: float,
) -> list[RetrievalHit]:
    scored = []
    for chunk in chunks:
        distance = l2_distance(query_embedding, chunk_embeddings[chunk.chunk_id])
        score = 1.0 / (1.0 + distance)
        lex = lexical_score(question.question, chunk.content)
        rerank = vector_weight * score + lexical_weight * lex
        scored.append((chunk, distance, score, lex, rerank))

    raw = sorted(scored, key=lambda item: item[1])[:raw_top_k]
    reranked = sorted(raw, key=lambda item: item[4], reverse=True)[:final_top_k]
    answer_key = compact_for_match(question.answer_source)
    hits = []
    for rank, (chunk, distance, score, lex, rerank) in enumerate(reranked, start=1):
        hits.append(
            RetrievalHit(
                rank=rank,
                chunk_id=chunk.chunk_id,
                page_start=chunk.page_start,
                page_end=chunk.page_end,
                distance=distance,
                score=score,
                lexical_score=lex,
                rerank_score=rerank,
                contains_answer=answer_key in compact_for_match(chunk.content),
            )
        )
    return hits


def metrics(evaluations: list[dict], final_top_k: int) -> dict:
    total = len(evaluations)
    hit_at_1 = sum(1 for item in evaluations if item["hits"] and item["hits"][0]["contains_answer"])
    hit_at_k = sum(1 for item in evaluations if any(hit["contains_answer"] for hit in item["hits"]))
    reciprocal_ranks = []
    precisions = []
    for item in evaluations:
        relevant_ranks = [hit["rank"] for hit in item["hits"] if hit["contains_answer"]]
        reciprocal_ranks.append(0.0 if not relevant_ranks else 1.0 / min(relevant_ranks))
        precisions.append(len(relevant_ranks) / final_top_k)
    return {
        "question_count": total,
        "hit_rate_at_1": hit_at_1 / total,
        f"hit_rate_at_{final_top_k}": hit_at_k / total,
        f"recall_at_{final_top_k}": hit_at_k / total,
        f"precision_at_{final_top_k}": sum(precisions) / total,
        "mrr": sum(reciprocal_ranks) / total,
        "mean_top1_distance": statistics.mean(item["hits"][0]["distance"] for item in evaluations if item["hits"]),
    }


def write_jsonl(path: Path, rows: Iterable[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as file:
        for row in rows:
            file.write(json.dumps(row, ensure_ascii=False) + "\n")


def write_report(path: Path, pdf_path: Path, pages: list[dict], chunks: list[Chunk], questions: list[Question], result: dict) -> None:
    metric = result["metrics"]
    baseline_metric = result["baseline_metrics"]
    metric_rows = "\n".join(
        f"| {key} | {baseline_metric[key]:.4f} | {metric[key]:.4f} | {metric[key] - baseline_metric[key]:+.4f} |"
        for key in [
            "hit_rate_at_1",
            "hit_rate_at_3",
            "recall_at_3",
            "precision_at_3",
            "mrr",
        ]
    )
    report = f"""# JChatMind RAG PDF 准确率评测报告

## 1. 测试对象

- 参考 PDF：`{pdf_path}`
- PDF 有文本页数：{len(pages)}
- 评测问题数：{len(questions)}
- 文档 chunk 数：{len(chunks)}
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
rawTopK = {result["config"]["raw_top_k"]}
finalTopK = {result["config"]["final_top_k"]}
vectorWeight = {result["config"]["vector_weight"]}
lexicalWeight = {result["config"]["lexical_weight"]}
```

## 5. 测试结果

| 指标 | 纯向量排序 | Hybrid Rerank 后 | 变化 |
| --- | ---: | ---: | ---: |
{metric_rows}

补充：

```text
mean_top1_distance = {metric["mean_top1_distance"]:.4f}
```

## 6. 产物

- 数据集：`docs/rag-evaluation/mysql-rag-eval-dataset.jsonl`
- 逐题结果：`docs/rag-evaluation/mysql-rag-eval-results.jsonl`
- 汇总指标：`docs/rag-evaluation/mysql-rag-eval-summary.json`
- 评测脚本：`scripts/rag_pdf_evaluation.py`

## 7. 结论

本次测试集超过 200 题，所有答案均来自 PDF 原文。

当前 RAG 在该 PDF 上的主要指标为：

- HitRate@1：{metric["hit_rate_at_1"]:.2%}
- Recall@3：{metric["recall_at_3"]:.2%}
- Precision@3：{metric["precision_at_3"]:.2%}
- MRR：{metric["mrr"]:.2%}

这些指标说明：在每个问题只要求召回答案所在 chunk 的口径下，当前 RAG 检索链路可以较稳定地把 PDF 中的答案依据召回给模型。

和纯向量排序相比，Hybrid Rerank 的变化为：

- HitRate@1：{metric["hit_rate_at_1"] - baseline_metric["hit_rate_at_1"]:+.2%}
- Recall@3：{metric["recall_at_3"] - baseline_metric["recall_at_3"]:+.2%}
- Precision@3：{metric["precision_at_3"] - baseline_metric["precision_at_3"]:+.2%}
- MRR：{metric["mrr"] - baseline_metric["mrr"]:+.2%}

## 8. 局限

- 本评测使用答案原文包含关系自动判定，适合检索层回归，不等价于最终大模型回答质量。
- PDF 抽取文本可能受版式、图片、表格影响；如果答案只存在于图片中，本测试无法覆盖。
- 问题由 PDF 原文句子自动构造，覆盖面大但表达方式偏直接；真实用户问题可能更口语化，需要另建人工 query 集补充。
"""
    path.write_text(report, encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pdf", required=True, type=Path)
    parser.add_argument("--out-dir", default=Path("docs/rag-evaluation"), type=Path)
    parser.add_argument("--cache", default=Path(".rag-eval-cache/embeddings.json"), type=Path)
    parser.add_argument("--model", default="bge-m3")
    parser.add_argument("--ollama-host", default="http://localhost:11434")
    parser.add_argument("--questions", default=QUESTION_COUNT, type=int)
    parser.add_argument("--raw-top-k", default=DEFAULT_RAW_TOP_K, type=int)
    parser.add_argument("--final-top-k", default=DEFAULT_FINAL_TOP_K, type=int)
    parser.add_argument("--vector-weight", default=DEFAULT_VECTOR_WEIGHT, type=float)
    parser.add_argument("--lexical-weight", default=DEFAULT_LEXICAL_WEIGHT, type=float)
    args = parser.parse_args()

    started = time.time()
    args.out_dir.mkdir(parents=True, exist_ok=True)

    pages = extract_pages(args.pdf)
    chunks = build_chunks(pages)
    questions = build_questions(chunks, args.questions)

    cache = load_embedding_cache(args.cache)
    chunk_embeddings = {}
    for index, chunk in enumerate(chunks, start=1):
        if index % 50 == 0:
            print(f"Embedding chunks {index}/{len(chunks)}")
            save_embedding_cache(args.cache, cache)
        chunk_embeddings[chunk.chunk_id] = embed(chunk.content, cache, args.model, args.ollama_host)

    evaluations = []
    baseline_evaluations = []
    for index, question in enumerate(questions, start=1):
        if index % 25 == 0:
            print(f"Evaluating questions {index}/{len(questions)}")
            save_embedding_cache(args.cache, cache)
        query_embedding = embed(question.question, cache, args.model, args.ollama_host)
        baseline_hits = retrieve(
            question,
            chunks,
            chunk_embeddings,
            query_embedding,
            args.raw_top_k,
            args.final_top_k,
            1.0,
            0.0,
        )
        hits = retrieve(
            question,
            chunks,
            chunk_embeddings,
            query_embedding,
            args.raw_top_k,
            args.final_top_k,
            args.vector_weight,
            args.lexical_weight,
        )
        evaluations.append(
            {
                **asdict(question),
                "hits": [asdict(hit) for hit in hits],
                "baseline_hits": [asdict(hit) for hit in baseline_hits],
                "top_k_contains_answer": any(hit.contains_answer for hit in hits),
                "baseline_top_k_contains_answer": any(hit.contains_answer for hit in baseline_hits),
            }
        )
        baseline_evaluations.append(
            {
                **asdict(question),
                "hits": [asdict(hit) for hit in baseline_hits],
            }
        )

    save_embedding_cache(args.cache, cache)
    dataset_path = args.out_dir / "mysql-rag-eval-dataset.jsonl"
    results_path = args.out_dir / "mysql-rag-eval-results.jsonl"
    summary_path = args.out_dir / "mysql-rag-eval-summary.json"
    report_path = args.out_dir / "mysql-rag-eval-report.md"

    write_jsonl(dataset_path, (asdict(question) for question in questions))
    write_jsonl(results_path, evaluations)

    summary = {
        "pdf": str(args.pdf),
        "config": {
            "raw_top_k": args.raw_top_k,
            "final_top_k": args.final_top_k,
            "vector_weight": args.vector_weight,
            "lexical_weight": args.lexical_weight,
        },
        "pages": len(pages),
        "chunks": len(chunks),
        "questions": len(questions),
        "baseline_metrics": metrics(baseline_evaluations, args.final_top_k),
        "metrics": metrics(evaluations, args.final_top_k),
        "elapsed_seconds": round(time.time() - started, 2),
    }
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    write_report(report_path, args.pdf, pages, chunks, questions, summary)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
