#!/usr/bin/env python3
"""Evaluate current production chunk JSONL with baseline vector search vs hybrid rerank."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import statistics
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable
from urllib import request


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
    "执行器",
    "连接器",
]


@dataclass(frozen=True)
class Chunk:
    chunk_id: str
    chunk_index: int
    document_id: str
    document_title: str
    heading_path: list[str]
    content: str
    embedding_text: str


@dataclass(frozen=True)
class Question:
    id: str
    question: str
    answer: str
    relevant_chunk_id: str
    answer_source: str


@dataclass(frozen=True)
class RetrievalHit:
    rank: int
    chunk_id: str
    chunk_index: int
    distance: float
    score: float
    lexical_score: float
    rerank_score: float
    contains_answer: bool
    filtered: bool = False
    filter_reason: str | None = None


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


def load_chunks(path: Path) -> list[Chunk]:
    chunks = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        chunk_index = int(row["chunkIndex"])
        document_title = row.get("sectionTitle") or "document"
        chunks.append(
            Chunk(
                chunk_id=f"chunk-{chunk_index:04d}",
                chunk_index=chunk_index,
                document_id="mysql-xiaolin-pdf",
                document_title=document_title,
                heading_path=row.get("headingPath") or [document_title],
                content=row.get("content") or "",
                embedding_text=row.get("embeddingText") or row.get("content") or "",
            )
        )
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
                    id=f"mysql-current-rag-{idx:03d}",
                    question=question_template(sentence, idx),
                    answer=sentence,
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
    codepoints = [char for char in text if is_cjk(char)]
    features = set(codepoints)
    for index in range(len(codepoints) - 1):
        features.add(codepoints[index] + codepoints[index + 1])
    return features


def is_cjk(char: str) -> bool:
    codepoint = ord(char)
    return (
        0x4E00 <= codepoint <= 0x9FFF
        or 0x3040 <= codepoint <= 0x30FF
        or 0xAC00 <= codepoint <= 0xD7AF
    )


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


def searchable_text(chunk: Chunk) -> str:
    return f"{chunk.document_title}\n{json.dumps(chunk.heading_path, ensure_ascii=False)}\n{chunk.content}"


def score_chunks(
    question: Question,
    chunks: list[Chunk],
    chunk_embeddings: dict[str, list[float]],
    query_embedding: list[float],
) -> list[tuple[Chunk, float, float, float]]:
    scored = []
    for chunk in chunks:
        distance = l2_distance(query_embedding, chunk_embeddings[chunk.chunk_id])
        vector_score = 1.0 / (1.0 + distance)
        lex = lexical_score(question.question, searchable_text(chunk))
        scored.append((chunk, distance, vector_score, lex))
    return scored


def build_hits(
    question: Question,
    ranked: list[tuple[Chunk, float, float, float, float]],
    top_k: int,
) -> list[RetrievalHit]:
    answer_key = compact_for_match(question.answer_source)
    hits = []
    for rank, (chunk, distance, vector_score, lex, rerank_score) in enumerate(ranked[:top_k], start=1):
        hits.append(
            RetrievalHit(
                rank=rank,
                chunk_id=chunk.chunk_id,
                chunk_index=chunk.chunk_index,
                distance=distance,
                score=vector_score,
                lexical_score=lex,
                rerank_score=rerank_score,
                contains_answer=answer_key in compact_for_match(chunk.content),
            )
        )
    return hits


def retrieve_vector(
    question: Question,
    chunks: list[Chunk],
    chunk_embeddings: dict[str, list[float]],
    query_embedding: list[float],
    final_top_k: int,
) -> list[RetrievalHit]:
    scored = [
        (chunk, distance, vector_score, lex, vector_score)
        for chunk, distance, vector_score, lex in score_chunks(question, chunks, chunk_embeddings, query_embedding)
    ]
    ranked = sorted(scored, key=lambda item: item[1])
    return build_hits(question, ranked, final_top_k)


def retrieve_hybrid(
    question: Question,
    chunks: list[Chunk],
    chunk_embeddings: dict[str, list[float]],
    query_embedding: list[float],
    raw_top_k: int,
    final_top_k: int,
    vector_weight: float,
    lexical_weight: float,
) -> list[RetrievalHit]:
    scored = score_chunks(question, chunks, chunk_embeddings, query_embedding)
    raw = sorted(scored, key=lambda item: item[1])[:raw_top_k]
    reranked = [
        (chunk, distance, vector_score, lex, vector_weight * vector_score + lexical_weight * lex)
        for chunk, distance, vector_score, lex in raw
    ]
    reranked.sort(key=lambda item: item[4], reverse=True)
    return build_hits(question, reranked, final_top_k)


def apply_production_policy(
    hits: list[RetrievalHit],
    final_top_k: int,
    max_chunks_per_document: int,
) -> list[RetrievalHit]:
    selected = []
    selected_count = 0
    for hit in hits:
        if selected_count < final_top_k and selected_count < max_chunks_per_document:
            selected_count += 1
            selected.append(asdict(hit) | {"rank": selected_count, "filtered": False, "filter_reason": None})
        else:
            selected.append(
                asdict(hit)
                | {
                    "rank": None,
                    "filtered": True,
                    "filter_reason": (
                        "too_many_chunks_from_same_document"
                        if selected_count >= max_chunks_per_document
                        else "exceeds_final_top_k"
                    ),
                }
            )
    return [RetrievalHit(**row) for row in selected]


def metrics(evaluations: list[dict], final_top_k: int, hits_key: str = "hits") -> dict:
    total = len(evaluations)
    hit_at_1 = 0
    hit_at_k = 0
    reciprocal_ranks = []
    precisions = []
    selected_counts = []

    for item in evaluations:
        hits = [hit for hit in item[hits_key] if not hit.get("filtered", False)]
        selected_counts.append(len(hits))
        hit_at_1 += 1 if hits and hits[0]["contains_answer"] else 0
        relevant_ranks = [hit["rank"] for hit in hits if hit["contains_answer"] and hit["rank"] is not None]
        hit_at_k += 1 if relevant_ranks else 0
        reciprocal_ranks.append(0.0 if not relevant_ranks else 1.0 / min(relevant_ranks))
        precisions.append(len(relevant_ranks) / final_top_k)

    return {
        "question_count": total,
        "hit_rate_at_1": hit_at_1 / total,
        f"hit_rate_at_{final_top_k}": hit_at_k / total,
        f"recall_at_{final_top_k}": hit_at_k / total,
        f"precision_at_{final_top_k}": sum(precisions) / total,
        "mrr": sum(reciprocal_ranks) / total,
        "mean_selected_count": statistics.mean(selected_counts),
    }


def write_jsonl(path: Path, rows: Iterable[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as file:
        for row in rows:
            file.write(json.dumps(row, ensure_ascii=False) + "\n")


def pct(value: float) -> str:
    return f"{value:.2%}"


def signed_pct(value: float) -> str:
    return f"{value:+.2%}"


def metric_table(before: dict, after: dict, final_top_k: int) -> str:
    keys = [
        ("准确率 / HitRate@1", "hit_rate_at_1"),
        (f"召回率 / Recall@{final_top_k}", f"recall_at_{final_top_k}"),
        (f"精确率 / Precision@{final_top_k}", f"precision_at_{final_top_k}"),
        ("MRR", "mrr"),
    ]
    return "\n".join(
        f"| {label} | {pct(before[key])} | {pct(after[key])} | {signed_pct(after[key] - before[key])} |"
        for label, key in keys
    )


def write_report(
    path: Path,
    chunks_path: Path,
    chunks: list[Chunk],
    questions: list[Question],
    summary: dict,
) -> None:
    final_top_k = summary["config"]["final_top_k"]
    report = f"""# 当前分块策略下的 RAG 优化前后对比

## 1. 评测口径

- 输入分块：`{chunks_path}`
- 分块来源：`图解MySQL-小林coding-亮白版-v2.0.pdf` 经当前正式分块策略得到的 JSONL
- 文档 chunk 数：{len(chunks)}
- 评测问题数：{len(questions)}
- Embedding 模型：`{summary["config"]["model"]}`

本报告固定同一批生产分块，只比较检索策略：

- 优化前：纯向量相似度排序，直接取 Top{final_top_k}
- 优化后：先按向量召回 rawTopK={summary["config"]["raw_top_k"]}，再用 `0.70 * vectorScore + 0.30 * lexicalScore` 重排，取 Top{final_top_k}

问题和标准答案都从当前 chunk 原文中自动构造；如果召回 chunk 包含标准答案原文，就认为检索命中。

## 2. Top{final_top_k} 检索能力对比

| 指标 | 优化前：纯向量 | 优化后：Hybrid Rerank | 提升 |
| --- | ---: | ---: | ---: |
{metric_table(summary["baseline_metrics"], summary["hybrid_metrics"], final_top_k)}

## 3. 按当前默认入 Prompt 策略后的结果

当前代码默认 `maxChunksPerDocument={summary["config"]["max_chunks_per_document"]}`。因为这次只测一个 PDF，所以最终最多会选入 {summary["config"]["max_chunks_per_document"]} 个 chunk；下面这组更接近当前线上配置真正塞进 prompt 的结果。

| 指标 | 优化前：纯向量 + policy | 优化后：Hybrid + policy | 提升 |
| --- | ---: | ---: | ---: |
{metric_table(summary["baseline_policy_metrics"], summary["hybrid_policy_metrics"], final_top_k)}

## 4. 关键结论

- 在固定当前 177 个正式 chunk 的前提下，Hybrid Rerank 将 Top1 准确率从 {pct(summary["baseline_metrics"]["hit_rate_at_1"])} 提升到 {pct(summary["hybrid_metrics"]["hit_rate_at_1"])}，提升 {signed_pct(summary["hybrid_metrics"]["hit_rate_at_1"] - summary["baseline_metrics"]["hit_rate_at_1"])}。
- Top{final_top_k} 召回率从 {pct(summary["baseline_metrics"][f"recall_at_{final_top_k}"])} 提升到 {pct(summary["hybrid_metrics"][f"recall_at_{final_top_k}"])}，提升 {signed_pct(summary["hybrid_metrics"][f"recall_at_{final_top_k}"] - summary["baseline_metrics"][f"recall_at_{final_top_k}"])}。
- 因为每个问题通常只有一个标准答案 chunk，Precision@{final_top_k} 的理论上限接近 `1/{final_top_k}`；它更适合作为排序质量的辅助指标，核心看 HitRate@1、Recall@{final_top_k} 和 MRR。

## 5. 产物

- 数据集：`docs/rag-evaluation/current-chunking-comparison/mysql-current-chunking-dataset.jsonl`
- 逐题结果：`docs/rag-evaluation/current-chunking-comparison/mysql-current-chunking-results.jsonl`
- 汇总指标：`docs/rag-evaluation/current-chunking-comparison/mysql-current-chunking-summary.json`
- 评测脚本：`scripts/rag_current_chunking_comparison.py`

## 6. 局限

- 这是检索层评测，不等价于最终大模型回答质量。
- 问题由原文自动构造，覆盖面较广，但比真实用户问题更贴近原文。
- PDF 转 Markdown 的文本质量仍受 PDF 版式、图片和换行影响。
"""
    path.write_text(report, encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--chunks", required=True, type=Path)
    parser.add_argument("--out-dir", default=Path("docs/rag-evaluation/current-chunking-comparison"), type=Path)
    parser.add_argument("--cache", default=Path(".rag-eval-cache/embeddings.json"), type=Path)
    parser.add_argument("--model", default="bge-m3")
    parser.add_argument("--ollama-host", default="http://localhost:11434")
    parser.add_argument("--questions", default=QUESTION_COUNT, type=int)
    parser.add_argument("--raw-top-k", default=DEFAULT_RAW_TOP_K, type=int)
    parser.add_argument("--final-top-k", default=DEFAULT_FINAL_TOP_K, type=int)
    parser.add_argument("--max-chunks-per-document", default=DEFAULT_MAX_CHUNKS_PER_DOCUMENT, type=int)
    parser.add_argument("--vector-weight", default=DEFAULT_VECTOR_WEIGHT, type=float)
    parser.add_argument("--lexical-weight", default=DEFAULT_LEXICAL_WEIGHT, type=float)
    args = parser.parse_args()

    started = time.time()
    args.out_dir.mkdir(parents=True, exist_ok=True)

    chunks = load_chunks(args.chunks)
    questions = build_questions(chunks, args.questions)

    cache = load_embedding_cache(args.cache)
    chunk_embeddings = {}
    for index, chunk in enumerate(chunks, start=1):
        if index % 25 == 0:
            print(f"Embedding chunks {index}/{len(chunks)}")
            save_embedding_cache(args.cache, cache)
        chunk_embeddings[chunk.chunk_id] = embed(chunk.embedding_text, cache, args.model, args.ollama_host)

    evaluations = []
    for index, question in enumerate(questions, start=1):
        if index % 25 == 0:
            print(f"Evaluating questions {index}/{len(questions)}")
            save_embedding_cache(args.cache, cache)
        query_embedding = embed(question.question, cache, args.model, args.ollama_host)
        baseline_hits = retrieve_vector(question, chunks, chunk_embeddings, query_embedding, args.final_top_k)
        hybrid_hits = retrieve_hybrid(
            question,
            chunks,
            chunk_embeddings,
            query_embedding,
            args.raw_top_k,
            args.final_top_k,
            args.vector_weight,
            args.lexical_weight,
        )
        baseline_policy_hits = apply_production_policy(
            baseline_hits,
            args.final_top_k,
            args.max_chunks_per_document,
        )
        hybrid_policy_hits = apply_production_policy(
            hybrid_hits,
            args.final_top_k,
            args.max_chunks_per_document,
        )
        evaluations.append(
            {
                **asdict(question),
                "baseline_hits": [asdict(hit) for hit in baseline_hits],
                "hybrid_hits": [asdict(hit) for hit in hybrid_hits],
                "baseline_policy_hits": [asdict(hit) for hit in baseline_policy_hits],
                "hybrid_policy_hits": [asdict(hit) for hit in hybrid_policy_hits],
            }
        )

    save_embedding_cache(args.cache, cache)

    dataset_path = args.out_dir / "mysql-current-chunking-dataset.jsonl"
    results_path = args.out_dir / "mysql-current-chunking-results.jsonl"
    summary_path = args.out_dir / "mysql-current-chunking-summary.json"
    report_path = args.out_dir / "mysql-current-chunking-report.md"

    write_jsonl(dataset_path, (asdict(question) for question in questions))
    write_jsonl(results_path, evaluations)

    summary = {
        "chunks_path": str(args.chunks),
        "config": {
            "model": args.model,
            "raw_top_k": args.raw_top_k,
            "final_top_k": args.final_top_k,
            "max_chunks_per_document": args.max_chunks_per_document,
            "vector_weight": args.vector_weight,
            "lexical_weight": args.lexical_weight,
        },
        "chunks": len(chunks),
        "questions": len(questions),
        "baseline_metrics": metrics(
            [{"hits": row["baseline_hits"]} for row in evaluations],
            args.final_top_k,
        ),
        "hybrid_metrics": metrics(
            [{"hits": row["hybrid_hits"]} for row in evaluations],
            args.final_top_k,
        ),
        "baseline_policy_metrics": metrics(
            [{"hits": row["baseline_policy_hits"]} for row in evaluations],
            args.final_top_k,
        ),
        "hybrid_policy_metrics": metrics(
            [{"hits": row["hybrid_policy_hits"]} for row in evaluations],
            args.final_top_k,
        ),
        "duration_seconds": round(time.time() - started, 2),
    }
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    write_report(report_path, args.chunks, chunks, questions, summary)

    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
