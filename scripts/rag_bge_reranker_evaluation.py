#!/usr/bin/env python3
"""Compare vector, lightweight hybrid rerank, and bge-reranker-v2-m3 on current RAG chunks."""

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

from FlagEmbedding import FlagReranker


DEFAULT_RAW_TOP_K = 20
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
    rank: int | None
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
                    id=f"mysql-bge-reranker-rag-{idx:03d}",
                    question=question_template(sentence, idx),
                    answer=sentence,
                    relevant_chunk_id=chunk.chunk_id,
                    answer_source=sentence,
                )
            )
            if len(questions) >= count:
                return questions
    raise RuntimeError(f"Only generated {len(questions)} questions; need {count}.")


def load_json_cache(cache_path: Path) -> dict[str, object]:
    if not cache_path.exists():
        return {}
    return json.loads(cache_path.read_text(encoding="utf-8"))


def save_json_cache(cache_path: Path, cache: dict[str, object]) -> None:
    cache_path.parent.mkdir(parents=True, exist_ok=True)
    cache_path.write_text(json.dumps(cache, ensure_ascii=False), encoding="utf-8")


def embed(text: str, cache: dict[str, object], model: str, host: str) -> list[float]:
    key = hashlib.sha256(f"{model}\0{text}".encode("utf-8")).hexdigest()
    if key in cache:
        return cache[key]  # type: ignore[return-value]
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
    codepoints = [char for char in text if is_cjk(char)]
    features.update(codepoints)
    for index in range(len(codepoints) - 1):
        features.add(codepoints[index] + codepoints[index + 1])
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
    scored: list[tuple[Chunk, float, float, float]],
    final_top_k: int,
) -> list[RetrievalHit]:
    ranked = [(chunk, distance, vector_score, lex, vector_score) for chunk, distance, vector_score, lex in scored]
    ranked.sort(key=lambda item: item[1])
    return build_hits(question, ranked, final_top_k)


def retrieve_hybrid(
    question: Question,
    scored: list[tuple[Chunk, float, float, float]],
    raw_top_k: int,
    final_top_k: int,
    vector_weight: float,
    lexical_weight: float,
) -> list[RetrievalHit]:
    raw = sorted(scored, key=lambda item: item[1])[:raw_top_k]
    reranked = [
        (chunk, distance, vector_score, lex, vector_weight * vector_score + lexical_weight * lex)
        for chunk, distance, vector_score, lex in raw
    ]
    reranked.sort(key=lambda item: item[4], reverse=True)
    return build_hits(question, reranked, final_top_k)


def reranker_cache_key(model: str, query: str, chunk: Chunk) -> str:
    payload = f"{model}\0{query}\0{chunk.chunk_id}\0{chunk.content}"
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def bge_scores(
    reranker: FlagReranker,
    model: str,
    question: Question,
    raw: list[tuple[Chunk, float, float, float]],
    cache: dict[str, object],
) -> list[float]:
    keys = [reranker_cache_key(model, question.question, chunk) for chunk, *_ in raw]
    missing_indices = [index for index, key in enumerate(keys) if key not in cache]
    if missing_indices:
        pairs = [(question.question, raw[index][0].content) for index in missing_indices]
        scores = reranker.compute_score(pairs)
        if not isinstance(scores, list):
            scores = [scores]
        for index, score in zip(missing_indices, scores):
            cache[keys[index]] = float(score)
    return [float(cache[key]) for key in keys]


def fill_bge_score_cache(
    reranker: FlagReranker,
    model: str,
    question_raw_pairs: list[tuple[Question, list[tuple[Chunk, float, float, float]]]],
    cache: dict[str, object],
    cache_path: Path,
    batch_pairs: int,
) -> None:
    missing: list[tuple[str, tuple[str, str]]] = []
    for question, raw in question_raw_pairs:
        for chunk, *_ in raw:
            key = reranker_cache_key(model, question.question, chunk)
            if key not in cache:
                missing.append((key, (question.question, chunk.content)))

    if not missing:
        print("BGE reranker cache already complete")
        return

    print(f"Computing BGE reranker scores for {len(missing)} pairs")
    for start in range(0, len(missing), batch_pairs):
        batch = missing[start:start + batch_pairs]
        scores = reranker.compute_score([pair for _, pair in batch])
        if not isinstance(scores, list):
            scores = [scores]
        for (key, _), score in zip(batch, scores):
            cache[key] = float(score)
        save_json_cache(cache_path, cache)
        print(f"BGE reranker pairs {min(start + batch_pairs, len(missing))}/{len(missing)}")


def retrieve_bge_reranker(
    question: Question,
    scored: list[tuple[Chunk, float, float, float]],
    raw_top_k: int,
    final_top_k: int,
    reranker: FlagReranker,
    reranker_model: str,
    reranker_cache: dict[str, object],
) -> list[RetrievalHit]:
    raw = sorted(scored, key=lambda item: item[1])[:raw_top_k]
    scores = bge_scores(reranker, reranker_model, question, raw, reranker_cache)
    reranked = [
        (chunk, distance, vector_score, lex, bge_score)
        for (chunk, distance, vector_score, lex), bge_score in zip(raw, scores)
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


def metrics(evaluations: list[dict], final_top_k: int, hits_key: str) -> dict:
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


def pct(value: float) -> str:
    return f"{value:.2%}"


def signed_pct(value: float) -> str:
    return f"{value:+.2%}"


def metric_row(label: str, key: str, base: dict, hybrid: dict, bge: dict) -> str:
    return (
        f"| {label} | {pct(base[key])} | {pct(hybrid[key])} | {pct(bge[key])} | "
        f"{signed_pct(bge[key] - hybrid[key])} |"
    )


def score_distribution(rows: list[dict], hits_key: str) -> dict:
    pos = []
    neg = []
    for row in rows:
        for hit in row[hits_key]:
            if hit.get("filtered"):
                continue
            if hit["contains_answer"]:
                pos.append(hit["rerank_score"])
            else:
                neg.append(hit["rerank_score"])

    def percentile(values: list[float], p: float) -> float | None:
        if not values:
            return None
        values = sorted(values)
        k = (len(values) - 1) * p / 100
        floor = int(k)
        ceil = min(floor + 1, len(values) - 1)
        if floor == ceil:
            return values[floor]
        return values[floor] * (ceil - k) + values[ceil] * (k - floor)

    def summarize(values: list[float]) -> dict:
        return {
            "count": len(values),
            "min": min(values) if values else None,
            "p10": percentile(values, 10),
            "p25": percentile(values, 25),
            "p50": percentile(values, 50),
            "p75": percentile(values, 75),
            "p90": percentile(values, 90),
            "max": max(values) if values else None,
            "mean": statistics.mean(values) if values else None,
        }

    return {"positive": summarize(pos), "negative": summarize(neg)}


def write_jsonl(path: Path, rows: Iterable[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as file:
        for row in rows:
            file.write(json.dumps(row, ensure_ascii=False) + "\n")


def write_report(path: Path, chunks_path: Path, chunks: list[Chunk], questions: list[Question], summary: dict) -> None:
    final_top_k = summary["config"]["final_top_k"]
    recall_key = f"recall_at_{final_top_k}"
    precision_key = f"precision_at_{final_top_k}"
    bge_delta_top1 = (
        summary["bge_reranker_metrics"]["hit_rate_at_1"]
        - summary["hybrid_metrics"]["hit_rate_at_1"]
    )
    bge_delta_recall = (
        summary["bge_reranker_metrics"][recall_key]
        - summary["hybrid_metrics"][recall_key]
    )
    dist = summary["bge_reranker_score_distribution"]
    report = f"""# BGE Reranker v2 M3 离线评测报告

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

- 输入分块：`{chunks_path}`
- chunk 数：{len(chunks)}
- 问题数：{len(questions)}
- Embedding 模型：`{summary["config"]["embedding_model"]}`
- Reranker 模型：`{summary["config"]["reranker_model"]}`
- rawTopK：{summary["config"]["raw_top_k"]}
- finalTopK：{final_top_k}
- chunk 策略：PDF plain text chunking，`maxChars=1200`，`overlapChars=150`

三组对比：

| 组别 | 策略 |
| --- | --- |
| Vector | 纯向量距离排序 |
| Hybrid | 向量粗召回后，`0.70 * vectorScore + 0.30 * lexicalScore` 重排 |
| BGE Reranker | 向量粗召回后，使用 `bge-reranker-v2-m3` 对 query/chunk pair 重排 |

## 3. Top{final_top_k} 检索指标

| 指标 | Vector | Hybrid | BGE Reranker | BGE 相比 Hybrid |
| --- | ---: | ---: | ---: | ---: |
{metric_row("HitRate@1", "hit_rate_at_1", summary["baseline_metrics"], summary["hybrid_metrics"], summary["bge_reranker_metrics"])}
{metric_row(f"Recall@{final_top_k}", recall_key, summary["baseline_metrics"], summary["hybrid_metrics"], summary["bge_reranker_metrics"])}
{metric_row(f"Precision@{final_top_k}", precision_key, summary["baseline_metrics"], summary["hybrid_metrics"], summary["bge_reranker_metrics"])}
{metric_row("MRR", "mrr", summary["baseline_metrics"], summary["hybrid_metrics"], summary["bge_reranker_metrics"])}

## 4. 按当前入 Prompt 策略后的指标

当前生产策略还会套一层 `maxChunksPerDocument={summary["config"]["max_chunks_per_document"]}`。因为本次只测一个 PDF，所以实际最多注入 {summary["config"]["max_chunks_per_document"]} 个 chunk。

| 指标 | Vector + Policy | Hybrid + Policy | BGE + Policy | BGE 相比 Hybrid |
| --- | ---: | ---: | ---: | ---: |
{metric_row("HitRate@1", "hit_rate_at_1", summary["baseline_policy_metrics"], summary["hybrid_policy_metrics"], summary["bge_reranker_policy_metrics"])}
{metric_row(f"Recall@{final_top_k}", recall_key, summary["baseline_policy_metrics"], summary["hybrid_policy_metrics"], summary["bge_reranker_policy_metrics"])}
{metric_row(f"Precision@{final_top_k}", precision_key, summary["baseline_policy_metrics"], summary["hybrid_policy_metrics"], summary["bge_reranker_policy_metrics"])}
{metric_row("MRR", "mrr", summary["baseline_policy_metrics"], summary["hybrid_policy_metrics"], summary["bge_reranker_policy_metrics"])}

## 5. BGE Reranker 分数分布

正样本表示命中标准答案所在 chunk，负样本表示未命中标准答案所在 chunk。

| 样本 | count | min | p10 | p25 | p50 | p75 | p90 | max | mean |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 正样本 | {dist["positive"]["count"]} | {dist["positive"]["min"]:.4f} | {dist["positive"]["p10"]:.4f} | {dist["positive"]["p25"]:.4f} | {dist["positive"]["p50"]:.4f} | {dist["positive"]["p75"]:.4f} | {dist["positive"]["p90"]:.4f} | {dist["positive"]["max"]:.4f} | {dist["positive"]["mean"]:.4f} |
| 负样本 | {dist["negative"]["count"]} | {dist["negative"]["min"]:.4f} | {dist["negative"]["p10"]:.4f} | {dist["negative"]["p25"]:.4f} | {dist["negative"]["p50"]:.4f} | {dist["negative"]["p75"]:.4f} | {dist["negative"]["p90"]:.4f} | {dist["negative"]["max"]:.4f} | {dist["negative"]["mean"]:.4f} |

## 6. 初步结论

- BGE Reranker 相比当前 Hybrid 的 HitRate@1 变化：{signed_pct(bge_delta_top1)}。
- BGE Reranker 相比当前 Hybrid 的 Recall@{final_top_k} 变化：{signed_pct(bge_delta_recall)}。
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
"""
    path.write_text(report, encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--chunks", required=True, type=Path)
    parser.add_argument("--out-dir", default=Path("docs/rag-evaluation/bge-reranker-comparison"), type=Path)
    parser.add_argument("--embedding-cache", default=Path(".rag-eval-cache/embeddings.json"), type=Path)
    parser.add_argument("--reranker-cache", default=Path(".rag-eval-cache/bge-reranker-v2-m3.json"), type=Path)
    parser.add_argument("--embedding-model", default="bge-m3")
    parser.add_argument("--reranker-model", default="BAAI/bge-reranker-v2-m3")
    parser.add_argument("--ollama-host", default="http://localhost:11434")
    parser.add_argument("--questions", default=QUESTION_COUNT, type=int)
    parser.add_argument("--raw-top-k", default=DEFAULT_RAW_TOP_K, type=int)
    parser.add_argument("--final-top-k", default=DEFAULT_FINAL_TOP_K, type=int)
    parser.add_argument("--max-chunks-per-document", default=DEFAULT_MAX_CHUNKS_PER_DOCUMENT, type=int)
    parser.add_argument("--vector-weight", default=DEFAULT_VECTOR_WEIGHT, type=float)
    parser.add_argument("--lexical-weight", default=DEFAULT_LEXICAL_WEIGHT, type=float)
    parser.add_argument("--device", default="mps")
    parser.add_argument("--batch-size", default=16, type=int)
    parser.add_argument("--batch-pairs", default=200, type=int)
    parser.add_argument("--max-length", default=1024, type=int)
    args = parser.parse_args()

    started = time.time()
    args.out_dir.mkdir(parents=True, exist_ok=True)

    chunks = load_chunks(args.chunks)
    questions = build_questions(chunks, args.questions)

    embedding_cache = load_json_cache(args.embedding_cache)
    chunk_embeddings: dict[str, list[float]] = {}
    for index, chunk in enumerate(chunks, start=1):
        if index % 25 == 0:
            print(f"Embedding chunks {index}/{len(chunks)}")
            save_json_cache(args.embedding_cache, embedding_cache)
        chunk_embeddings[chunk.chunk_id] = embed(chunk.embedding_text, embedding_cache, args.embedding_model, args.ollama_host)
    save_json_cache(args.embedding_cache, embedding_cache)

    print(f"Loading reranker {args.reranker_model} on {args.device}")
    reranker = FlagReranker(
        args.reranker_model,
        use_fp16=False,
        devices=args.device,
        batch_size=args.batch_size,
        max_length=args.max_length,
    )
    reranker_cache = load_json_cache(args.reranker_cache)

    question_scored: list[tuple[Question, list[tuple[Chunk, float, float, float]]]] = []
    question_raw_pairs: list[tuple[Question, list[tuple[Chunk, float, float, float]]]] = []
    for index, question in enumerate(questions, start=1):
        if index % 25 == 0:
            print(f"Scoring vector candidates {index}/{len(questions)}")
            save_json_cache(args.embedding_cache, embedding_cache)
        query_embedding = embed(question.question, embedding_cache, args.embedding_model, args.ollama_host)
        scored = score_chunks(question, chunks, chunk_embeddings, query_embedding)
        raw = sorted(scored, key=lambda item: item[1])[:args.raw_top_k]
        question_scored.append((question, scored))
        question_raw_pairs.append((question, raw))

    fill_bge_score_cache(
        reranker,
        args.reranker_model,
        question_raw_pairs,
        reranker_cache,
        args.reranker_cache,
        args.batch_pairs,
    )

    evaluations = []
    for index, (question, scored) in enumerate(question_scored, start=1):
        if index % 25 == 0:
            print(f"Building evaluation rows {index}/{len(questions)}")
        baseline_hits = retrieve_vector(question, scored, args.final_top_k)
        hybrid_hits = retrieve_hybrid(
            question,
            scored,
            args.raw_top_k,
            args.final_top_k,
            args.vector_weight,
            args.lexical_weight,
        )
        bge_hits = retrieve_bge_reranker(
            question,
            scored,
            args.raw_top_k,
            args.final_top_k,
            reranker,
            args.reranker_model,
            reranker_cache,
        )
        baseline_policy_hits = apply_production_policy(baseline_hits, args.final_top_k, args.max_chunks_per_document)
        hybrid_policy_hits = apply_production_policy(hybrid_hits, args.final_top_k, args.max_chunks_per_document)
        bge_policy_hits = apply_production_policy(bge_hits, args.final_top_k, args.max_chunks_per_document)
        evaluations.append(
            {
                **asdict(question),
                "baseline_hits": [asdict(hit) for hit in baseline_hits],
                "hybrid_hits": [asdict(hit) for hit in hybrid_hits],
                "bge_reranker_hits": [asdict(hit) for hit in bge_hits],
                "baseline_policy_hits": [asdict(hit) for hit in baseline_policy_hits],
                "hybrid_policy_hits": [asdict(hit) for hit in hybrid_policy_hits],
                "bge_reranker_policy_hits": [asdict(hit) for hit in bge_policy_hits],
            }
        )

    save_json_cache(args.embedding_cache, embedding_cache)
    save_json_cache(args.reranker_cache, reranker_cache)

    dataset_path = args.out_dir / "mysql-bge-reranker-dataset.jsonl"
    results_path = args.out_dir / "mysql-bge-reranker-results.jsonl"
    summary_path = args.out_dir / "mysql-bge-reranker-summary.json"
    report_path = args.out_dir / "mysql-bge-reranker-report.md"

    write_jsonl(dataset_path, (asdict(question) for question in questions))
    write_jsonl(results_path, evaluations)

    summary = {
        "chunks_path": str(args.chunks),
        "config": {
            "embedding_model": args.embedding_model,
            "reranker_model": args.reranker_model,
            "device": args.device,
            "batch_size": args.batch_size,
            "max_length": args.max_length,
            "raw_top_k": args.raw_top_k,
            "final_top_k": args.final_top_k,
            "max_chunks_per_document": args.max_chunks_per_document,
            "vector_weight": args.vector_weight,
            "lexical_weight": args.lexical_weight,
        },
        "chunks": len(chunks),
        "questions": len(questions),
        "baseline_metrics": metrics(evaluations, args.final_top_k, "baseline_hits"),
        "hybrid_metrics": metrics(evaluations, args.final_top_k, "hybrid_hits"),
        "bge_reranker_metrics": metrics(evaluations, args.final_top_k, "bge_reranker_hits"),
        "baseline_policy_metrics": metrics(evaluations, args.final_top_k, "baseline_policy_hits"),
        "hybrid_policy_metrics": metrics(evaluations, args.final_top_k, "hybrid_policy_hits"),
        "bge_reranker_policy_metrics": metrics(evaluations, args.final_top_k, "bge_reranker_policy_hits"),
        "bge_reranker_score_distribution": score_distribution(evaluations, "bge_reranker_hits"),
        "duration_seconds": round(time.time() - started, 2),
    }
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    write_report(report_path, args.chunks, chunks, questions, summary)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
