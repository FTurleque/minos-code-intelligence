#!/usr/bin/env python3
"""Evaluate M21-S8 STANDARD semantic/hybrid measurements before any backend migration."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

STANDARD = {
    "logical_file_count": 10_000,
    "symbol_count": 100_000,
    "occurrence_count": 500_000,
    "relationship_count": 250_000,
    "semantic_document_count": 210_000,
    "vector_dimensions": 384,
}

LATENCY_LIMITS_MS = {
    "vector-store-load": {"p95_ms": 1_500.0, "p99_ms": 3_000.0},
    "semantic-search": {"p95_ms": 2_500.0, "p99_ms": 5_000.0},
    "hybrid-search": {"p95_ms": 5_000.0, "p99_ms": 10_000.0},
    "hybrid-context": {"p95_ms": 6_000.0, "p99_ms": 12_000.0},
}

MAX_HEAP_RATIO = 0.80
MAX_INDEX_BYTES = 2 * 1024 * 1024 * 1024
MIN_REUSE_RATIO = 0.999


def load(path: Path) -> dict:
    if not path.is_file():
        raise ValueError(f"benchmark result is missing: {path}")
    return json.loads(path.read_text(encoding="utf-8-sig"))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("result")
    parser.add_argument("--expected-head", default="")
    parser.add_argument("--output", default="target/m21-s8/decision.json")
    args = parser.parse_args()

    try:
        data = load(Path(args.result))
        invariant_failures: list[str] = []
        performance_failures: list[str] = []

        if data.get("schema_version") != 1:
            invariant_failures.append(f"schema_version={data.get('schema_version')} expected=1")
        if data.get("profile") != "STANDARD":
            invariant_failures.append(f"profile={data.get('profile')} expected=STANDARD")
        if data.get("seed") != 16000031:
            invariant_failures.append(f"seed={data.get('seed')} expected=16000031")
        for field, expected in STANDARD.items():
            actual = data.get(field)
            if actual != expected:
                invariant_failures.append(f"{field}={actual} expected={expected}")
        if data.get("embedding_provider") != "minos-local-hash":
            invariant_failures.append(f"embedding_provider={data.get('embedding_provider')} expected=minos-local-hash")
        if data.get("linear_vector_scan_observed") is not True:
            invariant_failures.append("current linear vector scan was not reported")
        if data.get("incremental_embedded_added") != 0 or data.get("incremental_removed") != 0:
            invariant_failures.append("controlled incremental mutation changed semantic stable-key cardinality")
        if data.get("incremental_embedded_changed") != 3:
            invariant_failures.append(
                f"incremental_embedded_changed={data.get('incremental_embedded_changed')} expected=3"
            )
        reuse = float(data.get("incremental_reuse_ratio", 0.0))
        if reuse < MIN_REUSE_RATIO:
            performance_failures.append(f"incremental reuse ratio={reuse:.6f} min={MIN_REUSE_RATIO:.6f}")

        machine = data.get("machine") or {}
        actual_head = machine.get("head")
        if args.expected_head and actual_head != args.expected_head:
            invariant_failures.append(f"machine.head={actual_head} expected={args.expected_head}")

        max_heap = int(data.get("max_heap_bytes", 0) or 0)
        peak_heap = int(data.get("peak_heap_bytes", 0) or 0)
        heap_ratio = (peak_heap / max_heap) if max_heap > 0 else 1.0
        if max_heap <= 0:
            invariant_failures.append("max_heap_bytes is unavailable")
        elif heap_ratio >= MAX_HEAP_RATIO:
            performance_failures.append(f"peak heap ratio={heap_ratio:.4f} max<{MAX_HEAP_RATIO:.2f}")

        index_bytes = int(data.get("semantic_index_disk_size_bytes", 0) or 0)
        if index_bytes <= 0:
            invariant_failures.append("semantic index disk size is not positive")
        elif index_bytes > MAX_INDEX_BYTES:
            performance_failures.append(f"semantic index size={index_bytes} max={MAX_INDEX_BYTES}")

        operations = data.get("operations") or {}
        for operation, limits in LATENCY_LIMITS_MS.items():
            measured = operations.get(operation)
            if not isinstance(measured, dict):
                invariant_failures.append(f"missing operation measurements: {operation}")
                continue
            for metric, maximum in limits.items():
                value = float(measured.get(metric, float("inf")))
                if value > maximum:
                    performance_failures.append(
                        f"{operation}.{metric}={value:.3f}ms max={maximum:.3f}ms"
                    )

        total_memory = machine.get("total_physical_memory_bytes")
        rss = int(data.get("process_rss_bytes", 0) or 0)
        rss_ratio = None
        if total_memory:
            rss_ratio = rss / int(total_memory)

        if invariant_failures:
            decision = "INVALID_MEASUREMENT"
            status = "FAIL"
        elif performance_failures:
            decision = "OPTIMIZE_MEASURED_BOTTLENECK"
            status = "FAIL"
        else:
            decision = "KEEP_CURRENT_M20_BACKEND"
            status = "PASS"

        report = {
            "schemaVersion": 1,
            "source": str(Path(args.result)),
            "profile": data.get("profile"),
            "head": actual_head,
            "status": status,
            "decision": decision,
            "invariantFailures": invariant_failures,
            "performanceFailures": performance_failures,
            "thresholds": {
                "latencyMs": LATENCY_LIMITS_MS,
                "maxHeapRatioExclusive": MAX_HEAP_RATIO,
                "maxIndexBytes": MAX_INDEX_BYTES,
                "minIncrementalReuseRatio": MIN_REUSE_RATIO,
            },
            "measurements": {
                "documents": data.get("semantic_document_count"),
                "dimensions": data.get("vector_dimensions"),
                "initialIndexBuildMs": data.get("initial_index_build_ms"),
                "incrementalIndexRebuildMs": data.get("incremental_index_rebuild_ms"),
                "incrementalReuseRatio": reuse,
                "peakHeapRatio": round(heap_ratio, 6),
                "processRssBytes": rss,
                "processRssToPhysicalMemoryRatio": None if rss_ratio is None else round(rss_ratio, 6),
                "semanticIndexDiskSizeBytes": index_bytes,
                "operations": operations,
            },
        }
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

        print(
            "M21 S8 STANDARD MEASUREMENT "
            f"status={status} decision={decision} docs={data.get('semantic_document_count')} "
            f"dims={data.get('vector_dimensions')} reuse={reuse:.6f} "
            f"heapRatio={heap_ratio:.4f} indexBytes={index_bytes} rssBytes={rss}"
        )
        for operation, measured in operations.items():
            print(
                f"M21 S8 {operation}: p50={measured.get('p50_ms')}ms "
                f"p95={measured.get('p95_ms')}ms p99={measured.get('p99_ms')}ms"
            )
        for failure in invariant_failures:
            print(f"M21 S8 INVALID: {failure}", file=sys.stderr)
        for failure in performance_failures:
            print(f"M21 S8 BOTTLENECK: {failure}", file=sys.stderr)

        if status == "PASS":
            print("M21 S8 SEMANTIC SCALE DECISION SUCCESS")
            return 0
        return 2 if invariant_failures else 1
    except Exception as exc:
        print(f"M21 S8 RESULT CHECK FAILED: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
