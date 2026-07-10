#!/usr/bin/env python3
"""Serve BAAI/bge-reranker-v2-m3 behind a small HTTP /rerank API.

Request:
  POST /rerank
  {
    "model": "BAAI/bge-reranker-v2-m3",
    "query": "user question",
    "passages": ["chunk 1", "chunk 2"]
  }

Response:
  {
    "scores": [6.1, 2.4],
    "results": [{"index": 0, "score": 6.1}, {"index": 1, "score": 2.4}]
  }
"""

from __future__ import annotations

import argparse
import json
import logging
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Lock
from typing import Any


LOGGER = logging.getLogger("rag-bge-reranker")


class RerankerState:
    def __init__(
        self,
        model_name: str,
        device: str,
        batch_size: int,
        max_length: int,
    ) -> None:
        self.model_name = model_name
        self.device = device
        self.batch_size = batch_size
        self.max_length = max_length
        self._reranker = None
        self._inference_lock = Lock()

    @property
    def reranker(self):
        if self._reranker is None:
            from FlagEmbedding import FlagReranker

            LOGGER.info("Loading reranker model=%s device=%s", self.model_name, self.device)
            self._reranker = FlagReranker(
                self.model_name,
                use_fp16=False,
                devices=self.device,
                query_max_length=self.max_length,
                passage_max_length=self.max_length,
            )
            LOGGER.info("Reranker loaded")
        return self._reranker

    def score(self, query: str, passages: list[str]) -> list[float]:
        # FlagEmbedding inference on Apple MPS is not safe to run concurrently.
        # Serializing it also ensures the lazy model initialization happens once.
        with self._inference_lock:
            pairs = [[query, passage] for passage in passages]
            scores: list[float] = []
            for start in range(0, len(pairs), self.batch_size):
                batch = pairs[start : start + self.batch_size]
                batch_scores = self.reranker.compute_score(batch)
                if not isinstance(batch_scores, list):
                    try:
                        batch_scores = batch_scores.tolist()
                    except AttributeError:
                        batch_scores = [batch_scores]
                scores.extend(float(score) for score in batch_scores)
            return scores


def build_handler(state: RerankerState):
    class RerankHandler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            if self.path == "/health":
                self.write_json(200, {"status": "ok", "model": state.model_name})
                return
            self.write_json(404, {"error": "not_found"})

        def do_POST(self) -> None:
            if self.path != "/rerank":
                self.write_json(404, {"error": "not_found"})
                return

            try:
                payload = self.read_json()
                query = str(payload.get("query", "")).strip()
                passages = payload.get("passages")
                if not query:
                    self.write_json(400, {"error": "query is required"})
                    return
                if not isinstance(passages, list):
                    self.write_json(400, {"error": "passages must be a list"})
                    return

                normalized_passages = ["" if passage is None else str(passage) for passage in passages]
                scores = state.score(query, normalized_passages)
                results = [
                    {"index": index, "score": score}
                    for index, score in sorted(
                        enumerate(scores),
                        key=lambda item: item[1],
                        reverse=True,
                    )
                ]
                self.write_json(
                    200,
                    {
                        "scores": scores,
                        "results": results,
                        "usage": {"passageCount": len(normalized_passages)},
                    },
                )
            except Exception as exc:  # noqa: BLE001
                LOGGER.exception("Rerank request failed")
                self.write_json(500, {"error": str(exc)})

        def read_json(self) -> dict[str, Any]:
            content_length = int(self.headers.get("Content-Length", "0"))
            body = self.rfile.read(content_length)
            if not body:
                return {}
            return json.loads(body.decode("utf-8"))

        def write_json(self, status: int, payload: dict[str, Any]) -> None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, fmt: str, *args: Any) -> None:
            LOGGER.info("%s - %s", self.address_string(), fmt % args)

    return RerankHandler


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Serve bge-reranker-v2-m3 for JChatMind RAG.")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", default=8001, type=int)
    parser.add_argument("--model", default="BAAI/bge-reranker-v2-m3")
    parser.add_argument("--device", default="mps")
    parser.add_argument("--batch-size", default=16, type=int)
    parser.add_argument("--max-length", default=1024, type=int)
    return parser.parse_args()


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    args = parse_args()
    state = RerankerState(
        model_name=args.model,
        device=args.device,
        batch_size=args.batch_size,
        max_length=args.max_length,
    )
    server = ThreadingHTTPServer((args.host, args.port), build_handler(state))
    LOGGER.info("Reranker server listening on http://%s:%s", args.host, args.port)
    LOGGER.info("Health check: curl http://%s:%s/health", args.host, args.port)
    server.serve_forever()


if __name__ == "__main__":
    main()
