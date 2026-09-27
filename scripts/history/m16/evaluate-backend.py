#!/usr/bin/env python3
"""Evaluate M16 product thresholds and produce the backend decision report."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

THRESHOLDS = {
    "find-symbol": (250.0, 500.0),
    "find-usages": (250.0, 500.0),
    "dependencies": (250.0, 500.0),
    "dependents": (250.0, 500.0),
    "related-tests": (500.0, 1000.0),
    "search": (500.0, 1000.0),
    "architecture": (2000.0, 5000.0),
    "impact": (1000.0, 2500.0),
}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--current", type=Path, required=True)
    parser.add_argument("--sqlite", type=Path, required=True)
    parser.add_argument("--mcp", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    current = json.loads(args.current.read_text(encoding="utf-8-sig"))
    sqlite = json.loads(args.sqlite.read_text(encoding="utf-8-sig"))
    mcp = json.loads(args.mcp.read_text(encoding="utf-8-sig"))

    failures: list[dict[str, object]] = []
    for name, (p95_max, p99_max) in THRESHOLDS.items():
        stats = current["queries"][name]
        if stats["p95_ms"] > p95_max or stats["p99_ms"] > p99_max:
            failures.append({
                "scenario": name,
                "p95_ms": stats["p95_ms"],
                "p99_ms": stats["p99_ms"],
                "p95_max_ms": p95_max,
                "p99_max_ms": p99_max,
            })

    mcp_p95 = mcp["stdio_sequence"]["p95_ms"]
    mcp_p99 = mcp["stdio_sequence"]["p99_ms"]
    if mcp_p95 > 8000.0 or mcp_p99 > 20000.0:  # eight calls per measured sequence
        failures.append({
            "scenario": "MCP-sequence-8-calls",
            "p95_ms": mcp_p95,
            "p99_ms": mcp_p99,
            "p95_max_ms": 8000.0,
            "p99_max_ms": 20000.0,
        })

    cache_ok = (
        current["active_snapshot_full_loads"] == 1
        and current["query_view_builds"] == 1
        and mcp["backend_full_snapshot_load_delta"] <= 1
        and mcp["backend_query_view_build_delta"] <= 1
    )
    heap_ok = current["peak_heap_bytes"] < int(current["max_heap_bytes"] * 0.80)

    comparison: dict[str, object] = {}
    for name in sorted(set(current["queries"]) & set(sqlite["queries"])):
        current_p95 = float(current["queries"][name]["p95_ms"])
        sqlite_p95 = float(sqlite["queries"][name]["p95_ms"])
        improvement = 0.0 if current_p95 <= 0 else ((current_p95 - sqlite_p95) / current_p95) * 100.0
        comparison[name] = {
            "current_p95_ms": current_p95,
            "sqlite_p95_ms": sqlite_p95,
            "sqlite_improvement_percent": round(improvement, 2),
        }

    if not failures and cache_ok and heap_ok:
        decision = "RETAIN_FILE_SNAPSHOTS_PLUS_REBUILDABLE_MEMORY_INDEXES"
        rationale = (
            "The M15 backend meets every M16 STANDARD product threshold. "
            "Introducing SQLite would add runtime/backend complexity without a measured product bottleneck."
        )
        closeable = True
    else:
        decision = "BACKEND_CHANGE_REQUIRES_TARGETED_PROTOTYPE"
        rationale = (
            "At least one STANDARD threshold failed. M16 must not close until the failed bottleneck "
            "is addressed and remeasured on the same dataset/seed."
        )
        closeable = False

    result = {
        "decision": decision,
        "m16_closeable": closeable,
        "profile": current["profile"],
        "threshold_failures": failures,
        "cache_gate_pass": cache_ok,
        "heap_gate_pass": heap_ok,
        "sqlite_runtime_dependency_added": False,
        "sqlite_comparison": comparison,
        "rationale": rationale,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"M16 backend decision: {decision} closeable={closeable} failures={len(failures)}")
    return 0 if closeable else 2


if __name__ == "__main__":
    raise SystemExit(main())
