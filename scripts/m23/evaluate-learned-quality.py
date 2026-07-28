#!/usr/bin/env python3
"""M23 controlled quality gate for the explicitly configured local learned embedding model."""

from __future__ import annotations

import argparse
import ipaddress
import json
import math
import os
import re
import sys
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_FIXTURE = ROOT / "fixtures" / "m23" / "semantic-quality-v1.json"
DEFAULT_OUTPUT = ROOT / "target" / "m23-quality" / "learned-semantic-quality.json"
MAX_RESPONSE_BYTES = 16 * 1024 * 1024
TOKEN = re.compile(r"[A-Za-z0-9_]+")


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: N802
        return None


def require_env(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise RuntimeError(f"missing required environment variable: {name}")
    return value


def require_loopback_endpoint(value: str) -> str:
    parsed = urlparse(value)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise RuntimeError("MINOS_SEMANTIC_ENDPOINT must be an http(s) URL")
    host = parsed.hostname.lower()
    loopback = host == "localhost"
    if not loopback:
        try:
            loopback = ipaddress.ip_address(host).is_loopback
        except ValueError:
            loopback = False
    if not loopback:
        raise RuntimeError("M23 learned quality gate requires a loopback-only embedding endpoint")
    if parsed.username or parsed.password or parsed.fragment:
        raise RuntimeError("embedding endpoint must not contain credentials or a fragment")
    return value


def embed(opener, endpoint: str, model: str, dimensions: int, text: str, timeout: float) -> list[float]:
    body = json.dumps({"model": model, "input": text, "truncate": True}).encode("utf-8")
    request = Request(endpoint, data=body, method="POST", headers={
        "Content-Type": "application/json; charset=utf-8",
        "Accept": "application/json",
    })
    try:
        with opener.open(request, timeout=timeout) as response:
            raw = response.read(MAX_RESPONSE_BYTES + 1)
    except HTTPError as exc:
        detail = exc.read(4096).decode("utf-8", errors="replace")
        raise RuntimeError(f"embedding endpoint returned HTTP {exc.code}: {detail}") from exc
    except URLError as exc:
        raise RuntimeError(f"cannot reach local embedding endpoint: {exc}") from exc
    if len(raw) > MAX_RESPONSE_BYTES:
        raise RuntimeError("embedding response exceeds safety limit")
    payload = json.loads(raw.decode("utf-8"))
    embeddings = payload.get("embeddings")
    if not isinstance(embeddings, list) or len(embeddings) != 1 or not isinstance(embeddings[0], list):
        raise RuntimeError("embedding response must contain exactly one embeddings vector")
    vector = [float(value) for value in embeddings[0]]
    if len(vector) != dimensions:
        raise RuntimeError(f"embedding dimensions mismatch: expected {dimensions}, got {len(vector)}")
    if not all(math.isfinite(value) for value in vector):
        raise RuntimeError("embedding vector contains a non-finite value")
    return vector


def cosine(left: list[float], right: list[float]) -> float:
    dot = sum(a * b for a, b in zip(left, right, strict=True))
    left_norm = math.sqrt(sum(value * value for value in left))
    right_norm = math.sqrt(sum(value * value for value in right))
    if left_norm == 0.0 or right_norm == 0.0:
        return 0.0
    return max(-1.0, min(1.0, dot / (left_norm * right_norm)))


def tokens(text: str) -> set[str]:
    return {token.lower() for token in TOKEN.findall(text) if len(token) > 2}


def lexical_score(query: str, document: str) -> float:
    q = tokens(query)
    d = tokens(document)
    if not q or not d:
        return 0.0
    return len(q & d) / math.sqrt(len(q) * len(d))


def metrics(rankings: list[list[str]], relevant_sets: list[set[str]], k: int) -> dict[str, float]:
    recalls: list[float] = []
    reciprocal_ranks: list[float] = []
    ndcgs: list[float] = []
    for ranked, relevant in zip(rankings, relevant_sets, strict=True):
        top = ranked[:k]
        recalls.append(len(set(top) & relevant) / len(relevant))
        rr = 0.0
        for index, key in enumerate(ranked):
            if key in relevant:
                rr = 1.0 / (index + 1.0)
                break
        reciprocal_ranks.append(rr)
        dcg = sum(1.0 / math.log2(index + 2.0) for index, key in enumerate(top) if key in relevant)
        ideal = min(k, len(relevant))
        idcg = sum(1.0 / math.log2(index + 2.0) for index in range(ideal))
        ndcgs.append(0.0 if idcg == 0.0 else dcg / idcg)
    return {
        "recallAtK": sum(recalls) / len(recalls),
        "mrr": sum(reciprocal_ranks) / len(reciprocal_ranks),
        "ndcgAtK": sum(ndcgs) / len(ndcgs),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixture", type=Path, default=DEFAULT_FIXTURE)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--timeout", type=float, default=30.0)
    args = parser.parse_args()

    try:
        model = require_env("MINOS_SEMANTIC_MODEL")
        dimensions = int(require_env("MINOS_SEMANTIC_DIMENSIONS"))
        if dimensions < 32 or dimensions > 16_384:
            raise RuntimeError("MINOS_SEMANTIC_DIMENSIONS must be between 32 and 16384")
        endpoint = require_loopback_endpoint(
            os.environ.get("MINOS_SEMANTIC_ENDPOINT", "http://127.0.0.1:11434/api/embed").strip())
        fixture = json.loads(args.fixture.read_text(encoding="utf-8"))
        documents = fixture["documents"]
        queries = fixture["queries"]
        k = int(fixture["topK"])
        thresholds = fixture["thresholds"]
        if not documents or not queries or k < 1:
            raise RuntimeError("invalid M23 semantic quality fixture")

        opener = build_opener(NoRedirect())
        started = time.perf_counter()
        document_vectors: dict[str, list[float]] = {}
        for document in documents:
            document_vectors[document["key"]] = embed(
                opener, endpoint, model, dimensions, document["text"], args.timeout)

        semantic_rankings: list[list[str]] = []
        lexical_rankings: list[list[str]] = []
        relevant_sets: list[set[str]] = []
        per_query: list[dict[str, object]] = []
        known = set(document_vectors)
        for query in queries:
            relevant = set(query["relevant"])
            if not relevant or not relevant <= known:
                raise RuntimeError(f"query references unknown relevant keys: {query}")
            query_vector = embed(opener, endpoint, model, dimensions, query["text"], args.timeout)
            semantic = sorted(
                documents,
                key=lambda document: (-cosine(query_vector, document_vectors[document["key"]]), document["key"]),
            )
            lexical = sorted(
                documents,
                key=lambda document: (-lexical_score(query["text"], document["text"]), document["key"]),
            )
            semantic_keys = [document["key"] for document in semantic]
            lexical_keys = [document["key"] for document in lexical]
            semantic_rankings.append(semantic_keys)
            lexical_rankings.append(lexical_keys)
            relevant_sets.append(relevant)
            per_query.append({
                "query": query["text"],
                "relevant": sorted(relevant),
                "semanticTopK": semantic_keys[:k],
                "lexicalTopK": lexical_keys[:k],
            })

        semantic_metrics = metrics(semantic_rankings, relevant_sets, k)
        lexical_metrics = metrics(lexical_rankings, relevant_sets, k)
        failures = [
            f"{name}={semantic_metrics[name]:.6f} < threshold={float(thresholds[name]):.6f}"
            for name in ("recallAtK", "mrr", "ndcgAtK")
            if semantic_metrics[name] < float(thresholds[name])
        ]
        result = {
            "status": "PASS" if not failures else "FAIL",
            "fixtureVersion": fixture.get("version"),
            "provider": "minos-local-ollama",
            "model": model,
            "dimensions": dimensions,
            "endpoint": endpoint,
            "topK": k,
            "documents": len(documents),
            "queries": len(queries),
            "semantic": semantic_metrics,
            "lexicalBaseline": lexical_metrics,
            "thresholds": thresholds,
            "elapsedMillis": round((time.perf_counter() - started) * 1000.0, 3),
            "failures": failures,
            "perQuery": per_query,
        }
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        print(
            "M23 learned quality "
            f"Recall@{k}={semantic_metrics['recallAtK']:.6f} "
            f"MRR={semantic_metrics['mrr']:.6f} nDCG@{k}={semantic_metrics['ndcgAtK']:.6f} "
            f"model={model} dimensions={dimensions}"
        )
        print(
            "M23 lexical baseline "
            f"Recall@{k}={lexical_metrics['recallAtK']:.6f} "
            f"MRR={lexical_metrics['mrr']:.6f} nDCG@{k}={lexical_metrics['ndcgAtK']:.6f}"
        )
        if failures:
            for failure in failures:
                print(f"ERROR: {failure}", file=sys.stderr)
            print("M23 LEARNED SEMANTIC QUALITY FAILED", file=sys.stderr)
            return 1
        print("M23 LEARNED SEMANTIC QUALITY SUCCESS")
        return 0
    except Exception as exc:  # gate must fail closed with one explicit reason
        print(f"ERROR: {exc}", file=sys.stderr)
        print("M23 LEARNED SEMANTIC QUALITY FAILED", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
