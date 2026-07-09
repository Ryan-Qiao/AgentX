#!/usr/bin/env python3
"""Build a natural-question Markdown RAG dataset and compare retrieval strategies."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import random
import re
import statistics
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable
from urllib import request


DEFAULT_RAW_TOP_K = 20
DEFAULT_FINAL_TOP_K = 3
DEFAULT_VECTOR_WEIGHT = 0.70
DEFAULT_LEXICAL_WEIGHT = 0.30
DEFAULT_MAX_CHARS = 1200
DEFAULT_OVERLAP_CHARS = 150
DEFAULT_MIN_CHARS = 80
DEFAULT_QUESTIONS = 200


@dataclass(frozen=True)
class Chunk:
    chunk_id: str
    chunk_index: int
    document_id: str
    document_title: str
    heading_path: list[str]
    heading_level: int
    section_title: str
    section_chunk_index: int
    char_start: int
    char_end: int
    content: str
    embedding_text: str


@dataclass(frozen=True)
class Question:
    id: str
    question: str
    answer: str
    relevant_chunk_id: str
    answer_source: str
    heading_path: list[str]
    question_type: str


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


def normalize_text(text: str) -> str:
    text = text.replace("\u3000", " ")
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def compact_for_match(text: str) -> str:
    return re.sub(r"\s+", "", text)


def parse_markdown_sections(markdown: str, document_title: str) -> list[dict]:
    lines = markdown.splitlines()
    sections: list[dict] = []
    heading_stack: dict[int, str] = {}
    current: dict | None = None
    preamble: list[str] = []
    in_fence = False

    def flush_current() -> None:
        nonlocal current
        if current is None:
            return
        content = normalize_text("\n".join(current["lines"]))
        if content:
            sections.append(
                {
                    "title": current["title"],
                    "heading_level": current["heading_level"],
                    "heading_path": current["heading_path"],
                    "content": content,
                }
            )
        current = None

    for line in lines:
        stripped = line.strip()
        if re.match(r"^(```|~~~)", stripped):
            in_fence = not in_fence

        heading_match = None if in_fence else re.match(r"^(#{1,6})\s+(.+?)\s*#*\s*$", line)
        if heading_match:
            if current is None and preamble:
                content = normalize_text("\n".join(preamble))
                if content:
                    sections.append(
                        {
                            "title": document_title,
                            "heading_level": 1,
                            "heading_path": [document_title],
                            "content": content,
                        }
                    )
                preamble = []
            flush_current()
            level = len(heading_match.group(1))
            title = heading_match.group(2).strip()
            for stale in [key for key in heading_stack if key >= level]:
                del heading_stack[stale]
            heading_stack[level] = title
            current = {
                "title": title,
                "heading_level": level,
                "heading_path": [heading_stack[key] for key in sorted(heading_stack)],
                "lines": [],
            }
            continue

        if current is None:
            preamble.append(line)
        else:
            current["lines"].append(line)

    if current is None and preamble:
        content = normalize_text("\n".join(preamble))
        if content:
            sections.append(
                {
                    "title": document_title,
                    "heading_level": 1,
                    "heading_path": [document_title],
                    "content": content,
                }
            )
    flush_current()
    return sections


def split_section(content: str, max_chars: int, overlap_chars: int, min_chars: int) -> list[tuple[str, int, int]]:
    normalized = normalize_text(content)
    if len(normalized) <= max_chars:
        return [(normalized, 0, len(normalized))]
    effective_overlap = max(0, min(overlap_chars, max_chars - 1))
    slices = []
    start = 0
    while start < len(normalized):
        end = min(start + max_chars, len(normalized))
        if len(normalized) - end < min_chars and slices:
            end = len(normalized)
        slices.append((normalized[start:end].strip(), start, end))
        if end >= len(normalized):
            break
        start = max(end - effective_overlap, start + 1)
    return slices


def build_embedding_text(document_title: str, heading_path: list[str], content: str) -> str:
    heading = " > ".join(item for item in heading_path if item.strip())
    return f"文档：{document_title}\n章节：{heading}\n\n{content}".strip()


def build_chunks(markdown_path: Path, max_chars: int, overlap_chars: int, min_chars: int) -> list[Chunk]:
    document_title = markdown_path.name
    markdown = markdown_path.read_text(encoding="utf-8")
    sections = parse_markdown_sections(markdown, document_title)
    chunks: list[Chunk] = []
    for section in sections:
        slices = split_section(section["content"], max_chars, overlap_chars, min_chars)
        for section_chunk_index, (text, start, end) in enumerate(slices, start=1):
            chunk_index = len(chunks)
            digest = hashlib.sha1(f"{chunk_index}\0{text}".encode("utf-8")).hexdigest()[:10]
            chunks.append(
                Chunk(
                    chunk_id=f"chunk-{chunk_index:04d}-{digest}",
                    chunk_index=chunk_index,
                    document_id=markdown_path.stem,
                    document_title=document_title,
                    heading_path=section["heading_path"],
                    heading_level=section["heading_level"],
                    section_title=section["title"],
                    section_chunk_index=section_chunk_index,
                    char_start=start,
                    char_end=end,
                    content=text,
                    embedding_text=build_embedding_text(document_title, section["heading_path"], text),
                )
            )
    return chunks


def write_jsonl(path: Path, rows: Iterable[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as file:
        for row in rows:
            file.write(json.dumps(row, ensure_ascii=False) + "\n")


def load_jsonl(path: Path) -> list[dict]:
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            rows.append(json.loads(line))
    return rows


def load_chunks(path: Path) -> list[Chunk]:
    return [Chunk(**row) for row in load_jsonl(path)]


def load_questions(path: Path) -> list[Question]:
    return [Question(**row) for row in load_jsonl(path)]


def remove_code_fences(text: str) -> str:
    return re.sub(r"```.*?```|~~~.*?~~~", "", text, flags=re.S)


def sentence_split(text: str) -> list[str]:
    plain = remove_code_fences(text)
    plain = re.sub(r"https?://\S+", "", plain)
    plain = re.sub(r"`([^`]+)`", r"\1", plain)
    parts = re.split(r"(?<=[。！？；])\s*", plain)
    return [normalize_text(part) for part in parts if 45 <= len(normalize_text(part)) <= 260]


def is_good_candidate_chunk(chunk: Chunk) -> bool:
    if len(chunk.content) < 180:
        return False
    if len(re.findall(r"[\u4e00-\u9fff]", chunk.content)) < 80:
        return False
    code_lines = sum(1 for line in chunk.content.splitlines() if line.strip().startswith(("import ", "def ", "class ", "# ", "pip ", "python ")))
    return code_lines / max(1, len(chunk.content.splitlines())) < 0.55


def choose_answer_source(chunk: Chunk) -> str | None:
    sentences = sentence_split(chunk.content)
    if not sentences:
        return None
    scored = []
    for sentence in sentences:
        score = 0
        if any(term in sentence for term in ["LangChain", "LangGraph", "Agent", "智能体", "工具", "状态", "节点", "检索", "模型"]):
            score += 3
        if any(word in sentence for word in ["因为", "所以", "适合", "区别", "优势", "核心", "用于", "能够", "需要"]):
            score += 2
        score += min(len(sentence), 180) / 180
        scored.append((score, sentence))
    scored.sort(reverse=True, key=lambda item: item[0])
    return scored[0][1]


def longest_common_substring_length(left: str, right: str) -> int:
    left = compact_for_match(left)
    right = compact_for_match(right)
    previous = [0] * (len(right) + 1)
    best = 0
    for left_char in left:
        current = [0]
        for j, right_char in enumerate(right, start=1):
            value = previous[j - 1] + 1 if left_char == right_char else 0
            current.append(value)
            best = max(best, value)
        previous = current
    return best


def model_chat(prompt: str, model: str, provider: str) -> str:
    if provider == "deepseek":
        api_key = os.environ.get("DEEPSEEK_API_KEY")
        if not api_key:
            raise RuntimeError("DEEPSEEK_API_KEY is not set")
        url = os.environ.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com/v1").rstrip("/") + "/chat/completions"
        payload = {
            "model": model,
            "messages": [{"role": "user", "content": prompt}],
            "temperature": 0.7,
            "response_format": {"type": "json_object"},
        }
        req = request.Request(
            url,
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"},
            method="POST",
        )
    elif provider == "zhipu":
        api_key = os.environ.get("ZHIPUAI_API_KEY")
        if not api_key:
            raise RuntimeError("ZHIPUAI_API_KEY is not set")
        url = os.environ.get("ZHIPUAI_BASE_URL", "https://open.bigmodel.cn/api/paas/v4").rstrip("/") + "/chat/completions"
        payload = {
            "model": model,
            "messages": [{"role": "user", "content": prompt}],
            "temperature": 0.7,
            "response_format": {"type": "json_object"},
        }
        req = request.Request(
            url,
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"},
            method="POST",
        )
    else:
        raise ValueError(f"Unsupported provider: {provider}")
    with request.urlopen(req, timeout=120) as response:
        data = json.loads(response.read().decode("utf-8"))
    return data["choices"][0]["message"]["content"]


def question_prompt(items: list[dict]) -> str:
    return f"""
你要为 RAG 检索评测构造测试问题。每条输入都有一个 chunk_id、heading_path、answer_source。

请为每条输入生成：
- question：真实用户会问的问题，像学习者、后端面试准备者或开发者自然提出的问题。
- answer：用自然语言给出的参考答案，可以改写 answer_source，但不能引入外部知识。
- question_type：从 concept、scenario、troubleshooting、interview、comparison 中选一个。

严格要求：
1. 不要出现“根据文档”“原文”“这段内容”“上文提到”等测试痕迹。
2. question 不能照抄 answer_source，不能连续复用 answer_source 中超过 10 个中文字符。
3. question 不能只是把标题改成问句。
4. answer_source 必须原样保留，不要修改。
5. 只返回 JSON，格式为 {{"items":[...]}}。

输入：
{json.dumps(items, ensure_ascii=False)}
""".strip()


def parse_model_json(text: str) -> dict:
    text = text.strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*", "", text)
        text = re.sub(r"\s*```$", "", text)
    return json.loads(text)


def validate_question(row: dict, source: str) -> bool:
    question = normalize_text(row.get("question") or "")
    answer = normalize_text(row.get("answer") or "")
    if not (8 <= len(question) <= 120 and len(answer) >= 10):
        return False
    banned = ["根据文档", "原文", "这段内容", "上文", "文档中"]
    if any(word in question for word in banned):
        return False
    return longest_common_substring_length(question, source) <= 10


def fallback_question(item: dict, index: int) -> dict:
    heading = " / ".join(item["heading_path"][-2:])
    templates = [
        f"学习 {heading} 时，我应该重点理解什么？",
        f"面试里如果被问到 {heading}，怎么回答比较清楚？",
        f"实际开发中，{heading} 这个点主要解决什么问题？",
        f"{heading} 和普通做法相比，核心区别在哪里？",
    ]
    return {
        "id": f"easy-langent-natural-{index:03d}",
        "question": templates[index % len(templates)],
        "answer": item["answer_source"],
        "relevant_chunk_id": item["chunk_id"],
        "answer_source": item["answer_source"],
        "heading_path": item["heading_path"],
        "question_type": ["concept", "interview", "scenario", "comparison"][index % 4],
    }


def build_natural_questions(
    chunks: list[Chunk],
    count: int,
    provider: str,
    model: str,
    seed: int,
    batch_size: int,
) -> list[Question]:
    random.seed(seed)
    candidates = []
    used_sections = set()
    for chunk in chunks:
        if not is_good_candidate_chunk(chunk):
            continue
        source = choose_answer_source(chunk)
        if not source:
            continue
        section_key = " / ".join(chunk.heading_path)
        if section_key in used_sections and random.random() < 0.75:
            continue
        used_sections.add(section_key)
        candidates.append(
            {
                "chunk_id": chunk.chunk_id,
                "heading_path": chunk.heading_path,
                "answer_source": source,
            }
        )
    random.shuffle(candidates)
    candidates = candidates[: max(count * 2, count + 40)]

    rows = []
    for start in range(0, len(candidates), batch_size):
        batch = candidates[start:start + batch_size]
        try:
            response = model_chat(question_prompt(batch), model, provider)
            data = parse_model_json(response)
            generated = data.get("items") or []
        except Exception as exc:
            print(f"Question generation batch failed at {start}: {exc}")
            generated = []
        generated_by_id = {item.get("chunk_id"): item for item in generated if isinstance(item, dict)}
        for candidate in batch:
            item = generated_by_id.get(candidate["chunk_id"])
            if item and validate_question(item, candidate["answer_source"]):
                rows.append(
                    {
                        "id": f"easy-langent-natural-{len(rows) + 1:03d}",
                        "question": normalize_text(item["question"]),
                        "answer": normalize_text(item["answer"]),
                        "relevant_chunk_id": candidate["chunk_id"],
                        "answer_source": candidate["answer_source"],
                        "heading_path": candidate["heading_path"],
                        "question_type": item.get("question_type") or "concept",
                    }
                )
            else:
                rows.append(fallback_question(candidate, len(rows) + 1))
            if len(rows) >= count:
                return [Question(**row) for row in rows]
        print(f"Generated questions {len(rows)}/{count}")
        time.sleep(0.2)
    if len(rows) < count:
        raise RuntimeError(f"Only generated {len(rows)} questions; need {count}")
    return [Question(**row) for row in rows[:count]]


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
    return 0x4E00 <= codepoint <= 0x9FFF or 0x3040 <= codepoint <= 0x30FF or 0xAC00 <= codepoint <= 0xD7AF


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
        lex = lexical_score(question.question, chunk.embedding_text)
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


def retrieve_vector(question: Question, scored: list[tuple[Chunk, float, float, float]], final_top_k: int) -> list[RetrievalHit]:
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


def fill_bge_score_cache(
    reranker,
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


def retrieve_bge(
    question: Question,
    raw: list[tuple[Chunk, float, float, float]],
    final_top_k: int,
    reranker_model: str,
    reranker_cache: dict[str, object],
) -> list[RetrievalHit]:
    reranked = []
    for chunk, distance, vector_score, lex in raw:
        key = reranker_cache_key(reranker_model, question.question, chunk)
        reranked.append((chunk, distance, vector_score, lex, float(reranker_cache[key])))
    reranked.sort(key=lambda item: item[4], reverse=True)
    return build_hits(question, reranked, final_top_k)


def metrics(evaluations: list[dict], final_top_k: int, hits_key: str) -> dict:
    total = len(evaluations)
    hit_at_1 = 0
    hit_at_k = 0
    reciprocal_ranks = []
    precisions = []
    for item in evaluations:
        hits = item[hits_key]
        hit_at_1 += 1 if hits and hits[0]["contains_answer"] else 0
        ranks = [hit["rank"] for hit in hits if hit["contains_answer"]]
        hit_at_k += 1 if ranks else 0
        reciprocal_ranks.append(0.0 if not ranks else 1.0 / min(ranks))
        precisions.append(len(ranks) / final_top_k)
    return {
        "question_count": total,
        "hit_rate_at_1": hit_at_1 / total,
        f"recall_at_{final_top_k}": hit_at_k / total,
        f"precision_at_{final_top_k}": sum(precisions) / total,
        "mrr": sum(reciprocal_ranks) / total,
    }


def score_distribution(rows: list[dict], hits_key: str) -> dict:
    pos = []
    neg = []
    for row in rows:
        for hit in row[hits_key]:
            (pos if hit["contains_answer"] else neg).append(hit["rerank_score"])

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


def pct(value: float) -> str:
    return f"{value:.2%}"


def signed_pct(value: float) -> str:
    return f"{value:+.2%}"


def metric_row(label: str, key: str, base: dict, hybrid: dict, bge: dict) -> str:
    return (
        f"| {label} | {pct(base[key])} | {pct(hybrid[key])} | {pct(bge[key])} | "
        f"{signed_pct(bge[key] - hybrid[key])} |"
    )


def fmt_num(value: float | None) -> str:
    return "-" if value is None else f"{value:.4f}"


def write_report(path: Path, markdown_path: Path, chunks: list[Chunk], questions: list[Question], summary: dict) -> None:
    final_top_k = summary["config"]["final_top_k"]
    recall_key = f"recall_at_{final_top_k}"
    precision_key = f"precision_at_{final_top_k}"
    dist = summary["bge_score_distribution"]
    examples = "\n".join(
        f"- Q：{question.question}\n  - evidence：{question.answer_source[:120]}..."
        for question in questions[:5]
    )
    report = f"""# Markdown 自然问题 RAG 评测报告

## 1. 评测目标

基于原生 Markdown 文档 `{markdown_path}` 构造一套更接近真实用户问法的 RAG 检索测试集，对比三种检索策略：

| 方法 | 说明 |
| --- | --- |
| Vector | 纯向量相似度排序 |
| Hybrid | 向量粗召回 rawTopK 后，用 `0.70 * vectorScore + 0.30 * lexicalScore` 重排 |
| BGE Reranker | 向量粗召回 rawTopK 后，用 `bge-reranker-v2-m3` 对 query/chunk pair 重排 |

## 2. 数据构造

- 原始文档：`{markdown_path}`
- chunk 数：{len(chunks)}
- 测试问题数：{len(questions)}
- 分块策略：使用 Python 脚本模拟业务代码 `MarkdownChunkingServiceImpl` 的 Markdown heading section chunking 逻辑；核心参数与业务配置保持一致，`maxChars={summary["config"]["max_chars"]}`、`overlapChars={summary["config"]["overlap_chars"]}`。脚本通过识别 fenced code block 避免将代码块内 `#` 注释误判为标题，但本次评测没有直接调用 Java 业务分块服务。
- 问题构造：先从目标 chunk 中固定 `answer_source` 作为标准证据，再让模型改写为自然用户问题和参考答案。
- 校验规则：问题不能出现“根据文档/原文/这段内容”等测试痕迹，且不能连续复用证据文本超过 10 个中文字符。

示例问题：

{examples}

## 3. 检索配置

```text
embeddingModel = {summary["config"]["embedding_model"]}
rerankerModel = {summary["config"]["reranker_model"]}
rawTopK = {summary["config"]["raw_top_k"]}
finalTopK = {final_top_k}
vectorWeight = {summary["config"]["vector_weight"]}
lexicalWeight = {summary["config"]["lexical_weight"]}
```

## 4. Top{final_top_k} 指标

| 指标 | Vector | Hybrid | BGE Reranker | BGE 相比 Hybrid |
| --- | ---: | ---: | ---: | ---: |
{metric_row("HitRate@1", "hit_rate_at_1", summary["vector_metrics"], summary["hybrid_metrics"], summary["bge_metrics"])}
{metric_row(f"Recall@{final_top_k}", recall_key, summary["vector_metrics"], summary["hybrid_metrics"], summary["bge_metrics"])}
{metric_row(f"Precision@{final_top_k}", precision_key, summary["vector_metrics"], summary["hybrid_metrics"], summary["bge_metrics"])}
{metric_row("MRR", "mrr", summary["vector_metrics"], summary["hybrid_metrics"], summary["bge_metrics"])}

## 5. BGE Reranker 分数分布

| 样本 | count | min | p10 | p25 | p50 | p75 | p90 | max | mean |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 正样本 | {dist["positive"]["count"]} | {fmt_num(dist["positive"]["min"])} | {fmt_num(dist["positive"]["p10"])} | {fmt_num(dist["positive"]["p25"])} | {fmt_num(dist["positive"]["p50"])} | {fmt_num(dist["positive"]["p75"])} | {fmt_num(dist["positive"]["p90"])} | {fmt_num(dist["positive"]["max"])} | {fmt_num(dist["positive"]["mean"])} |
| 负样本 | {dist["negative"]["count"]} | {fmt_num(dist["negative"]["min"])} | {fmt_num(dist["negative"]["p10"])} | {fmt_num(dist["negative"]["p25"])} | {fmt_num(dist["negative"]["p50"])} | {fmt_num(dist["negative"]["p75"])} | {fmt_num(dist["negative"]["p90"])} | {fmt_num(dist["negative"]["max"])} | {fmt_num(dist["negative"]["mean"])} |

## 6. 结论

- 在这套自然问题测试集上，BGE Reranker 相比 Hybrid 的 HitRate@1 变化：{signed_pct(summary["bge_metrics"]["hit_rate_at_1"] - summary["hybrid_metrics"]["hit_rate_at_1"])}。
- BGE Reranker 相比 Hybrid 的 Recall@{final_top_k} 变化：{signed_pct(summary["bge_metrics"][recall_key] - summary["hybrid_metrics"][recall_key])}。
- 这套数据比之前“原文线索型”问题更接近真实用户表达，因此更适合判断 reranker 是否能处理改写、场景化和面试表达类问题。

## 7. 产物

- Chunks：`docs/rag-evaluation/easy-langent-natural/easy-langent-chunks.jsonl`
- 测试集：`docs/rag-evaluation/easy-langent-natural/easy-langent-natural-dataset.jsonl`
- 逐题结果：`docs/rag-evaluation/easy-langent-natural/easy-langent-natural-results.jsonl`
- 汇总指标：`docs/rag-evaluation/easy-langent-natural/easy-langent-natural-summary.json`
- 评测脚本：`scripts/rag_markdown_natural_eval.py`
"""
    path.write_text(report, encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--markdown", required=True, type=Path)
    parser.add_argument("--out-dir", default=Path("docs/rag-evaluation/easy-langent-natural"), type=Path)
    parser.add_argument("--embedding-cache", default=Path(".rag-eval-cache/markdown-natural-embeddings.json"), type=Path)
    parser.add_argument("--reranker-cache", default=Path(".rag-eval-cache/markdown-natural-bge-reranker.json"), type=Path)
    parser.add_argument("--embedding-model", default="bge-m3")
    parser.add_argument("--reranker-model", default="BAAI/bge-reranker-v2-m3")
    parser.add_argument("--ollama-host", default="http://localhost:11434")
    parser.add_argument("--provider", default="deepseek", choices=["deepseek", "zhipu"])
    parser.add_argument("--generation-model", default="deepseek-chat")
    parser.add_argument("--questions", default=DEFAULT_QUESTIONS, type=int)
    parser.add_argument("--raw-top-k", default=DEFAULT_RAW_TOP_K, type=int)
    parser.add_argument("--final-top-k", default=DEFAULT_FINAL_TOP_K, type=int)
    parser.add_argument("--vector-weight", default=DEFAULT_VECTOR_WEIGHT, type=float)
    parser.add_argument("--lexical-weight", default=DEFAULT_LEXICAL_WEIGHT, type=float)
    parser.add_argument("--max-chars", default=DEFAULT_MAX_CHARS, type=int)
    parser.add_argument("--overlap-chars", default=DEFAULT_OVERLAP_CHARS, type=int)
    parser.add_argument("--min-chars", default=DEFAULT_MIN_CHARS, type=int)
    parser.add_argument("--seed", default=42, type=int)
    parser.add_argument("--question-batch-size", default=8, type=int)
    parser.add_argument("--reranker-batch-pairs", default=160, type=int)
    parser.add_argument("--device", default="mps")
    parser.add_argument("--max-length", default=1024, type=int)
    args = parser.parse_args()

    started = time.time()
    args.out_dir.mkdir(parents=True, exist_ok=True)

    chunks_path = args.out_dir / "easy-langent-chunks.jsonl"
    dataset_path = args.out_dir / "easy-langent-natural-dataset.jsonl"
    results_path = args.out_dir / "easy-langent-natural-results.jsonl"
    summary_path = args.out_dir / "easy-langent-natural-summary.json"
    report_path = args.out_dir / "easy-langent-natural-report.md"

    if chunks_path.exists():
        chunks = load_chunks(chunks_path)
    else:
        chunks = build_chunks(args.markdown, args.max_chars, args.overlap_chars, args.min_chars)
        write_jsonl(chunks_path, (asdict(chunk) for chunk in chunks))
    print(f"Chunks: {len(chunks)}")

    if dataset_path.exists():
        questions = load_questions(dataset_path)
    else:
        questions = build_natural_questions(
            chunks,
            args.questions,
            args.provider,
            args.generation_model,
            args.seed,
            args.question_batch_size,
        )
        write_jsonl(dataset_path, (asdict(question) for question in questions))
    print(f"Questions: {len(questions)}")

    embedding_cache = load_json_cache(args.embedding_cache)
    chunk_embeddings: dict[str, list[float]] = {}
    for index, chunk in enumerate(chunks, start=1):
        if index == 1 or index % 50 == 0:
            print(f"Embedding chunks {index}/{len(chunks)}")
        chunk_embeddings[chunk.chunk_id] = embed(chunk.embedding_text, embedding_cache, args.embedding_model, args.ollama_host)
        if index % 50 == 0:
            save_json_cache(args.embedding_cache, embedding_cache)
    query_embeddings: dict[str, list[float]] = {}
    for index, question in enumerate(questions, start=1):
        if index == 1 or index % 25 == 0:
            print(f"Embedding questions {index}/{len(questions)}")
        query_embeddings[question.id] = embed(question.question, embedding_cache, args.embedding_model, args.ollama_host)
        if index % 25 == 0:
            save_json_cache(args.embedding_cache, embedding_cache)
    save_json_cache(args.embedding_cache, embedding_cache)

    scored_by_question = {}
    raw_by_question = []
    for question in questions:
        scored = score_chunks(question, chunks, chunk_embeddings, query_embeddings[question.id])
        scored_by_question[question.id] = scored
        raw_by_question.append((question, sorted(scored, key=lambda item: item[1])[: args.raw_top_k]))

    from FlagEmbedding import FlagReranker

    print(f"Loading reranker {args.reranker_model} on {args.device}")
    reranker = FlagReranker(
        args.reranker_model,
        use_fp16=False,
        devices=args.device,
        query_max_length=args.max_length,
        passage_max_length=args.max_length,
    )
    reranker_cache = load_json_cache(args.reranker_cache)
    fill_bge_score_cache(
        reranker,
        args.reranker_model,
        raw_by_question,
        reranker_cache,
        args.reranker_cache,
        args.reranker_batch_pairs,
    )
    save_json_cache(args.reranker_cache, reranker_cache)

    evaluations = []
    for index, question in enumerate(questions, start=1):
        if index == 1 or index % 25 == 0:
            print(f"Retrieving {index}/{len(questions)}")
        scored = scored_by_question[question.id]
        raw = sorted(scored, key=lambda item: item[1])[: args.raw_top_k]
        vector_hits = retrieve_vector(question, scored, args.final_top_k)
        hybrid_hits = retrieve_hybrid(
            question,
            scored,
            args.raw_top_k,
            args.final_top_k,
            args.vector_weight,
            args.lexical_weight,
        )
        bge_hits = retrieve_bge(question, raw, args.final_top_k, args.reranker_model, reranker_cache)
        evaluations.append(
            asdict(question)
            | {
                "vector_hits": [asdict(hit) for hit in vector_hits],
                "hybrid_hits": [asdict(hit) for hit in hybrid_hits],
                "bge_hits": [asdict(hit) for hit in bge_hits],
            }
        )

    write_jsonl(results_path, evaluations)
    summary = {
        "markdown_path": str(args.markdown),
        "chunks_path": str(chunks_path),
        "dataset_path": str(dataset_path),
        "results_path": str(results_path),
        "config": {
            "embedding_model": args.embedding_model,
            "reranker_model": args.reranker_model,
            "generation_provider": args.provider,
            "generation_model": args.generation_model,
            "raw_top_k": args.raw_top_k,
            "final_top_k": args.final_top_k,
            "vector_weight": args.vector_weight,
            "lexical_weight": args.lexical_weight,
            "max_chars": args.max_chars,
            "overlap_chars": args.overlap_chars,
            "min_chars": args.min_chars,
        },
        "chunks": len(chunks),
        "questions": len(questions),
        "vector_metrics": metrics(evaluations, args.final_top_k, "vector_hits"),
        "hybrid_metrics": metrics(evaluations, args.final_top_k, "hybrid_hits"),
        "bge_metrics": metrics(evaluations, args.final_top_k, "bge_hits"),
        "bge_score_distribution": score_distribution(evaluations, "bge_hits"),
        "elapsed_seconds": round(time.time() - started, 2),
    }
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    write_report(report_path, args.markdown, chunks, questions, summary)
    print(f"Report: {report_path}")


if __name__ == "__main__":
    main()
