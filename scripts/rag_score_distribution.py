#!/usr/bin/env python3
"""Plot BGE reranker rawTopK score distributions from the offline evaluation cache."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
from scipy.stats import gaussian_kde


DEFAULT_MODEL = "BAAI/bge-reranker-v2-m3"


def load_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def cache_key(model: str, question: str, chunk: dict) -> str:
    payload = f"{model}\0{question}\0{chunk['chunk_id']}\0{chunk['content']}"
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def collect_scores(
    questions: list[dict],
    chunks: list[dict],
    score_cache: dict[str, float],
    model: str,
) -> tuple[np.ndarray, np.ndarray]:
    positive: list[float] = []
    negative: list[float] = []
    for question in questions:
        candidate_count = 0
        for chunk in chunks:
            key = cache_key(model, question["question"], chunk)
            if key not in score_cache:
                continue
            candidate_count += 1
            target = positive if chunk["chunk_id"] == question["relevant_chunk_id"] else negative
            target.append(float(score_cache[key]))
        if candidate_count != 20:
            raise ValueError(f"{question['id']} has {candidate_count} cached candidates; expected 20")
    return np.asarray(positive), np.asarray(negative)


def plot_distribution(positive: np.ndarray, negative: np.ndarray, output: Path, threshold: float) -> None:
    plt.rcParams["font.sans-serif"] = ["PingFang SC", "Hiragino Sans GB", "Arial Unicode MS", "DejaVu Sans"]
    plt.rcParams["axes.unicode_minus"] = False

    all_scores = np.concatenate([positive, negative])
    bin_width = 0.5
    lower = math.floor(all_scores.min() / bin_width) * bin_width
    upper = math.ceil(all_scores.max() / bin_width) * bin_width
    bins = np.arange(lower, upper + bin_width, bin_width)
    centers = (bins[:-1] + bins[1:]) / 2
    counts, _ = np.histogram(all_scores, bins=bins)

    figure, axes = plt.subplots(2, 1, figsize=(11.5, 8.2), constrained_layout=True)
    figure.suptitle("BGE Reranker rawTop20 分数分布", fontsize=16, fontweight="semibold")

    axes[0].fill_between(centers, counts, step="mid", alpha=0.22, color="#4C78A8")
    axes[0].plot(centers, counts, marker="o", markersize=3, linewidth=1.8, color="#4C78A8", label="全部候选（5440）")
    axes[0].axvline(threshold, color="#2A9D8F", linestyle="--", linewidth=2, label=f"建议阈值 {threshold:.1f}")
    axes[0].set_ylabel("样本数量 / 0.5 分区间")
    axes[0].set_xlabel("BGE rerankScore")
    axes[0].set_title("实际频数：候选主要集中在低分区间")
    axes[0].grid(axis="y", alpha=0.2)
    axes[0].legend(frameon=False)

    grid = np.linspace(lower, upper, 500)
    positive_density = gaussian_kde(positive)(grid)
    negative_density = gaussian_kde(negative)(grid)
    axes[1].plot(grid, positive_density, linewidth=2.2, color="#4C78A8", label=f"正样本（{len(positive)}）")
    axes[1].plot(grid, negative_density, linewidth=2.2, color="#F28E2B", label=f"负样本（{len(negative)}）")
    axes[1].fill_between(grid, positive_density, alpha=0.12, color="#4C78A8")
    axes[1].fill_between(grid, negative_density, alpha=0.12, color="#F28E2B")
    axes[1].axvline(threshold, color="#2A9D8F", linestyle="--", linewidth=2, label=f"建议阈值 {threshold:.1f}")
    axes[1].set_ylabel("归一化密度")
    axes[1].set_xlabel("BGE rerankScore")
    axes[1].set_title("正负样本密度：曲线存在重叠，分布不服从单一正态分布")
    axes[1].grid(axis="y", alpha=0.2)
    axes[1].legend(frameon=False)

    output.parent.mkdir(parents=True, exist_ok=True)
    figure.savefig(output, format=output.suffix.removeprefix("."), dpi=180)
    plt.close(figure)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--evaluation-dir", type=Path, default=Path("docs/rag-evaluation/easy-langent-natural"))
    parser.add_argument("--cache", type=Path, default=Path(".rag-eval-cache/easy-langent-natural-bge-reranker.json"))
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--threshold", type=float, default=1.0)
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("docs/rag-evaluation/easy-langent-natural/bge-reranker-score-distribution.svg"),
    )
    args = parser.parse_args()

    questions = load_jsonl(args.evaluation_dir / "easy-langent-natural-dataset.jsonl")
    chunks = load_jsonl(args.evaluation_dir / "easy-langent-chunks.jsonl")
    score_cache = json.loads(args.cache.read_text(encoding="utf-8"))
    positive, negative = collect_scores(questions, chunks, score_cache, args.model)
    plot_distribution(positive, negative, args.output, args.threshold)
    print(f"Wrote {args.output} using {len(positive)} positive and {len(negative)} negative scores")


if __name__ == "__main__":
    main()
