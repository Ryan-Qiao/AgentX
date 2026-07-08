#!/usr/bin/env python3
"""Build a natural-language RAG dataset from Markdown chunks with DeepSeek.

Workflow:
1. Load one chunk at a time.
2. Send a fixed prompt + chunk content to DeepSeek.
3. Validate the model output locally.
4. Append accepted items to JSONL immediately.
5. Rewrite the full JSON wrapper after each accepted chunk for easy recovery.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Optional
from urllib import request


SYSTEM_PROMPT = """你是一个 RAG 评测数据集构造专家。

我会给你一个 Markdown 文档 chunk。请你为每个合格 chunk（意思是有值得提问的内容，而不是简单的一小段话） 生成 1 -2条自然语言问题。

这些问题用于评估 RAG 检索系统能否从知识库中召回正确 chunk，所以问题必须模拟真实用户提问，而不是照抄文档。

请严格遵守以下规则：

1. 问题必须像真实用户、学习者、开发者或后端面试准备者会问的问题。
2. 问题不要出现“根据文档”“原文中”“这段内容”“上文提到”“材料里说”等测试痕迹。
3. 问题不要直接复用 heading_path，也不要只是把标题改成问句。
4. 问题不能与 answer_source 过于相似，不能连续复用 answer_source 中超过 10 个中文字符。
5. answer_source 必须从 chunk.content 中原样摘取，不能改写、不能扩写、不能使用外部知识。
6. answer 是面向用户的参考答案，可以自然改写 answer_source，但不能引入 chunk 之外的信息。
7. 如果 chunk 主要是代码、日志、运行输出、目录、提交要求、纯清单、表格残片，跳过它。
8. 如果 chunk 信息太碎、无法形成一个真实用户会问的问题，跳过它。
9. 一个 chunk 最多生成 1-2 条测试样本。
10. 尽量覆盖不同问题类型：concept、scenario、troubleshooting、interview、comparison。

输出必须是合法 JSON，不要输出解释文字。

输出格式：

{
  "items": [
    {
      "id": "natural-rag-001",
      "question": "真实用户自然提问",
      "answer": "自然语言参考答案",
      "answer_source": "从 chunk.content 中原样摘取的证据文本",
      "relevant_chunk_id": "对应的 chunk_id",
      "heading_path": ["章节1", "章节2"],
      "question_type": "concept"
    }
  ]
}

输入格式
{
  "chunks": [
    {
      "chunk_id": "chunk-0001",
      "heading_path": ["第一章 LangChain与LangGraph框架认知", "1.2 LangChain与LangGraph的定义与核心区别"],
      "content": "这里放 chunk 正文..."
    }
  ]
}

输出结构
{
  "items": [
    {
      "id": "natural-rag-001",
      "question": "如果我要做一个有多步骤决策的 Agent，为什么 LangGraph 会比只用 LangChain 更合适？",
      "answer": "LangGraph 更适合复杂流程编排，因为它以状态机为核心，能管理带循环、有状态的流程，也适合多智能体协作。",
      "answer_source": "LangGraph 则以状态机为核心，能轻松实现 “检索→判断→生成→迭代” 这类带循环、有状态的复杂流程，以及多智能体协作。",
      "relevant_chunk_id": "chunk-0012",
      "heading_path": ["第一章 LangChain与LangGraph框架认知", "1.2 LangChain与LangGraph的定义与核心区别", "1.2.2 LangGraph：复杂应用的“架构设计框架”"],
      "question_type": "scenario"
    }
  ]
}
"""

BANNED_PHRASES = ["根据文档", "原文中", "这段内容", "上文提到", "材料里说", "根据材料"]
QUESTION_TYPES = ["concept", "scenario", "troubleshooting", "interview", "comparison"]


@dataclass(frozen=True)
class Chunk:
    chunk_id: str
    heading_path: list[str]
    content: str


def normalize_text(text: str) -> str:
    text = text.replace("\u3000", " ")
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def compact(text: str) -> str:
    return re.sub(r"\s+", "", text)


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    if not path.exists():
        return rows
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            rows.append(json.loads(line))
    return rows


def write_jsonl_append(path: Path, row: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as f:
        f.write(json.dumps(row, ensure_ascii=False) + "\n")


def write_json(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"items": rows}, ensure_ascii=False, indent=2), encoding="utf-8")


def load_chunks(path: Path) -> list[Chunk]:
    chunks = []
    for row in load_jsonl(path):
        chunks.append(
            Chunk(
                chunk_id=row["chunk_id"],
                heading_path=row["heading_path"],
                content=row["content"],
            )
        )
    return chunks


def count_cjk(text: str) -> int:
    return sum(1 for ch in text if "\u4e00" <= ch <= "\u9fff")


def longest_common_substring(left: str, right: str) -> int:
    left = compact(left)
    right = compact(right)
    if not left or not right:
        return 0
    prev = [0] * (len(right) + 1)
    best = 0
    for ch_left in left:
        cur = [0]
        for j, ch_right in enumerate(right, start=1):
            value = prev[j - 1] + 1 if ch_left == ch_right else 0
            cur.append(value)
            best = max(best, value)
        prev = cur
    return best


def split_blocks(content: str) -> list[str]:
    return [block for block in re.split(r"\n{2,}", content) if block.strip()]


def block_stats(block: str) -> dict[str, int]:
    lines = block.splitlines()
    code_lines = sum(
        1
        for line in lines
        if line.strip().startswith(
            (
                "```",
                "~~~",
                "import ",
                "from ",
                "def ",
                "class ",
                "pip ",
                "python ",
                "# ",
                "@tool",
            )
        )
    )
    table_lines = sum(1 for line in lines if line.count("|") >= 2)
    bullet_lines = sum(1 for line in lines if re.match(r"^\s*([-*+]|[0-9]+\.)\s+", line))
    return {
        "chars": len(block),
        "cjk": count_cjk(block),
        "code_lines": code_lines,
        "table_lines": table_lines,
        "bullet_lines": bullet_lines,
        "line_count": len(lines),
    }


def is_chunk_too_technical(chunk: Chunk) -> bool:
    content = chunk.content
    if len(content) < 80 or count_cjk(content) < 25:
        return True
    blocks = split_blocks(content)
    if not blocks:
        return True
    score = 0
    codeish = 0
    tableish = 0
    for block in blocks:
        stats = block_stats(block)
        score += 1
        if stats["code_lines"] >= max(2, stats["line_count"] // 2):
            codeish += 1
        if stats["table_lines"] >= max(3, stats["line_count"] // 2):
            tableish += 1
    return (codeish / score) >= 0.55 or (tableish / score) >= 0.55


def build_prompt(chunk: Chunk) -> str:
    payload = {
        "chunk_id": chunk.chunk_id,
        "heading_path": chunk.heading_path,
        "content": chunk.content,
    }
    return f"输入：\n{json.dumps(payload, ensure_ascii=False)}"


def post_deepseek(prompt: str, model: str, temperature: float) -> str:
    api_key = os.environ.get("DEEPSEEK_API_KEY")
    if not api_key:
        raise RuntimeError("DEEPSEEK_API_KEY is not set")
    base_url = os.environ.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com/v1").rstrip("/")
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": prompt},
        ],
        "temperature": temperature,
        "response_format": {"type": "json_object"},
    }
    req = request.Request(
        f"{base_url}/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
        },
        method="POST",
    )
    with request.urlopen(req, timeout=180) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return data["choices"][0]["message"]["content"]


def parse_json(text: str) -> dict[str, Any]:
    text = text.strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*", "", text)
        text = re.sub(r"\s*```$", "", text)
    return json.loads(text)


def validate_item(item: dict[str, Any], chunk: Chunk) -> bool:
    question = normalize_text(str(item.get("question", "")))
    answer = normalize_text(str(item.get("answer", "")))
    answer_source = item.get("answer_source", "")
    heading_path = item.get("heading_path", [])
    qtype = item.get("question_type", "")

    if not question or not answer or not answer_source:
        return False
    if not (8 <= len(question) <= 120):
        return False
    if not (10 <= len(answer) <= 500):
        return False
    if qtype not in QUESTION_TYPES:
        return False
    if any(bad in question for bad in BANNED_PHRASES):
        return False

    # heading_path should not be copied into the question.
    joined_heading = " / ".join(chunk.heading_path)
    if joined_heading and compact(joined_heading) in compact(question):
        return False

    # question should not be too close to answer_source.
    if longest_common_substring(question, answer_source) > 10:
        return False

    # answer_source must be an exact substring of the chunk content.
    if answer_source not in chunk.content:
        return False

    # basic structural checks.
    if item.get("relevant_chunk_id") != chunk.chunk_id:
        return False
    if heading_path != chunk.heading_path:
        return False
    return True


def repair_prompt(chunk: Chunk, bad_output: str, reason: str) -> str:
    return f"""上一次输出不符合要求，问题如下：
{reason}

请严格重试，只输出 JSON，不要解释。

这次仍然只能基于下面这个 chunk 生成 0-2 条自然语言问题；如果不适合提问，直接返回 {{\"items\":[]}}。

chunk:
{json.dumps({"chunk_id": chunk.chunk_id, "heading_path": chunk.heading_path, "content": chunk.content}, ensure_ascii=False)}

上一次输出：
{bad_output}
"""


def load_existing_rows(path: Path) -> list[dict[str, Any]]:
    return load_jsonl(path)


def build_existing_chunk_set(rows: list[dict[str, Any]]) -> set[str]:
    return {row["relevant_chunk_id"] for row in rows if "relevant_chunk_id" in row}


def next_id(rows: list[dict[str, Any]]) -> int:
    max_n = 0
    for row in rows:
        value = str(row.get("id", ""))
        m = re.match(r"^natural-rag-(\d+)$", value)
        if m:
            max_n = max(max_n, int(m.group(1)))
    return max_n + 1


def build_final_json(rows: list[dict[str, Any]], path: Path) -> None:
    write_json(path, rows)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--chunks",
        type=Path,
        default=Path("/Users/ryan/Workspace/Projects/Github/AgentX/docs/rag-evaluation/easy-langent-natural/easy-langent-chunks.jsonl"),
    )
    parser.add_argument(
        "--out-jsonl",
        type=Path,
        default=Path("/Users/ryan/Workspace/Projects/Github/AgentX/docs/rag-evaluation/easy-langent-natural/easy-langent-natural-dataset.jsonl"),
    )
    parser.add_argument(
        "--out-json",
        type=Path,
        default=Path("/Users/ryan/Workspace/Projects/Github/AgentX/docs/rag-evaluation/easy-langent-natural/easy-langent-natural-dataset.json"),
    )
    parser.add_argument("--model", default="deepseek-chat")
    parser.add_argument("--temperature", type=float, default=0.2)
    parser.add_argument("--sleep-seconds", type=float, default=0.5)
    parser.add_argument("--max-chunks", type=int, default=0, help="0 means no limit")
    parser.add_argument("--limit-items", type=int, default=0, help="0 means no limit")
    parser.add_argument("--reset", action="store_true")
    args = parser.parse_args()

    if args.reset:
        for path in [args.out_jsonl, args.out_json]:
            if path.exists():
                path.unlink()

    chunks = load_chunks(args.chunks)
    existing_rows = load_existing_rows(args.out_jsonl)
    processed_chunk_ids = build_existing_chunk_set(existing_rows)
    items = list(existing_rows)
    counter = next_id(items)

    processed = 0
    for chunk in chunks:
        if args.limit_items and len(items) >= args.limit_items:
            break
        if args.max_chunks and processed >= args.max_chunks:
            break
        if chunk.chunk_id in processed_chunk_ids:
            continue

        processed += 1
        if is_chunk_too_technical(chunk):
            # Still mark as processed by skipping; the chunk is not suitable.
            continue

        prompt = build_prompt(chunk)
        raw_output = ""
        parsed: Optional[dict[str, Any]] = None
        accepted_items: list[dict[str, Any]] = []

        for attempt in range(2):
            try:
                raw_output = post_deepseek(prompt if attempt == 0 else repair_prompt(chunk, raw_output, "local validation failed"), args.model, args.temperature)
                parsed = parse_json(raw_output)
            except Exception:
                parsed = None
                continue

            candidate_items = parsed.get("items") if isinstance(parsed, dict) else None
            if not isinstance(candidate_items, list):
                candidate_items = []

            accepted_items = []
            for item in candidate_items:
                if not isinstance(item, dict):
                    continue
                if validate_item(item, chunk):
                    accepted_items.append(item)

            if accepted_items:
                break

        if accepted_items:
            for item in accepted_items[:2]:
                if args.limit_items and len(items) >= args.limit_items:
                    break
                row = {
                    "id": f"natural-rag-{counter:03d}",
                    "question": normalize_text(item["question"]),
                    "answer": normalize_text(item["answer"]),
                    "answer_source": item["answer_source"],
                    "relevant_chunk_id": chunk.chunk_id,
                    "heading_path": chunk.heading_path,
                    "question_type": item["question_type"],
                }
                write_jsonl_append(args.out_jsonl, row)
                items.append(row)
                counter += 1

            processed_chunk_ids.add(chunk.chunk_id)
            build_final_json(items, args.out_json)

        time.sleep(args.sleep_seconds)

    build_final_json(items, args.out_json)
    print(json.dumps({"items": len(items)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
