#!/usr/bin/env python3
"""Validate M16 benchmark outputs and write a stable summary."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

QUERY_LIMITS = {
    "find-symbol": (250.0, 500.0),
    "find-usages": (250.0, 500.0),
    "dependencies": (250.0, 500.0),
    "dependents": (250.0, 500.0),
    "related-tests": (500.0, 1000.0),
    "search": (500.0, 1000.0),
    "architecture": (2000.0, 5000.0),
    "impact": (1000.0, 2500.0),
}

MCP_TOOL_LIMITS = (1000.0, 2500.0)


def load(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--scale", type=Path, required=True)
    parser.add_argument("--mcp", type=Path, required=True)
    parser.add_argument("--indexing", type=Path, required=True)
    parser.add_argument("--retention", type=Path, required=True)
    parser.add_argument("--backend", type=Path, required=True)
    parser.add_argument("--output-json", type=Path, required=True)
    parser.add_argument("--output-md", type=Path, required=True)
    args = parser.parse_args()

    scale = load(args.scale)
    mcp = load(args.mcp)
    indexing = load(args.indexing)
    retention = load(args.retention)
    backend = load(args.backend)

    failures: list[str] = []
    if scale.get("profile") != "STANDARD":
        failures.append(f"qualification profile must be STANDARD, got {scale.get('profile')}")
    expected_counts = (10_000, 100_000, 500_000, 250_000)
    actual_counts = (
        scale.get("logical_file_count"), scale.get("symbol_count"),
        scale.get("occurrence_count"), scale.get("relationship_count"),
    )
    if actual_counts != expected_counts:
        failures.append(f"STANDARD cardinalities mismatch: {actual_counts}")
    if scale.get("active_snapshot_full_loads") != 1:
        failures.append(f"expected exactly one full snapshot load, got {scale.get('active_snapshot_full_loads')}")
    if scale.get("query_view_builds") != 1:
        failures.append(f"expected exactly one query-view build, got {scale.get('query_view_builds')}")
    if scale.get("peak_heap_bytes", 0) >= int(scale.get("max_heap_bytes", 1) * 0.80):
        failures.append("peak heap reached/exceeded 80% of max heap")
    if scale.get("process_rss_bytes", 0) <= 0:
        failures.append("process RSS was not measured")
    if not scale.get("machine"):
        failures.append("machine metadata is missing")

    for name, (p95_max, p99_max) in QUERY_LIMITS.items():
        stats = scale["queries"][name]
        if stats["p95_ms"] > p95_max:
            failures.append(f"{name} p95 {stats['p95_ms']}ms > {p95_max}ms")
        if stats["p99_ms"] > p99_max:
            failures.append(f"{name} p99 {stats['p99_ms']}ms > {p99_max}ms")

    if mcp.get("backend_full_snapshot_load_delta", 99) > 1:
        failures.append("MCP backend sustained load caused more than one full snapshot load")
    if mcp.get("backend_query_view_build_delta", 99) > 1:
        failures.append("MCP backend sustained load caused more than one query-view build")
    for tool, stats in mcp.get("stdio_tools", {}).items():
        if stats["p95_ms"] > MCP_TOOL_LIMITS[0]:
            failures.append(f"{tool} MCP p95 {stats['p95_ms']}ms > {MCP_TOOL_LIMITS[0]}ms")
        if stats["p99_ms"] > MCP_TOOL_LIMITS[1]:
            failures.append(f"{tool} MCP p99 {stats['p99_ms']}ms > {MCP_TOOL_LIMITS[1]}ms")

    if indexing.get("full_mode") != "FULL":
        failures.append(f"initial indexing mode must be FULL, got {indexing.get('full_mode')}")
    if indexing.get("none_mode") != "NONE":
        failures.append(f"unchanged indexing mode must be NONE, got {indexing.get('none_mode')}")
    if indexing.get("incremental_capability_qualified") is not False:
        failures.append("indexing benchmark must not invent incremental provider capability")
    if indexing.get("full_peak_rss_bytes", 0) <= 0:
        failures.append("FULL indexing peak RSS was not measured")

    if retention.get("snapshot_count_after", 99) > 3:
        failures.append("snapshot retention exceeds active + 2 historical files")
    if retention.get("run_count_after", 99) > 30:
        failures.append("run retention exceeds 20 succeeded + 10 non-succeeded runs")
    if not retention.get("protected_latest_run_present"):
        failures.append("latestRunId was not protected by run retention")
    if retention.get("active_snapshot_id") != "snapshot-6":
        failures.append("active snapshot was changed by compaction")

    if not backend.get("m16_closeable"):
        failures.append("backend decision report says M16 is not closeable")
    if backend.get("decision") != "RETAIN_FILE_SNAPSHOTS_PLUS_REBUILDABLE_MEMORY_INDEXES":
        failures.append(f"unexpected backend decision: {backend.get('decision')}")

    summary = {
        "status": "PASS" if not failures else "FAIL",
        "failures": failures,
        "profile": scale["profile"],
        "head": scale["machine"].get("head"),
        "machine": scale["machine"],
        "cardinalities": {
            "files": scale["logical_file_count"],
            "symbols": scale["symbol_count"],
            "occurrences": scale["occurrence_count"],
            "relationships": scale["relationship_count"],
        },
        "memory": {
            "peak_heap_bytes": scale["peak_heap_bytes"],
            "retained_heap_bytes": scale["retained_heap_bytes"],
            "process_rss_bytes": scale["process_rss_bytes"],
            "max_heap_bytes": scale["max_heap_bytes"],
        },
        "disk": {
            "snapshot_disk_size_bytes": scale["snapshot_disk_size_bytes"],
            "sqlite_comparison_disk_size_bytes": load(args.backend.parent / "sqlite.json").get("disk_size_bytes")
            if (args.backend.parent / "sqlite.json").exists() else None,
        },
        "cache": {
            "full_loads": scale["active_snapshot_full_loads"],
            "query_view_builds": scale["query_view_builds"],
            "cache_hits": scale["query_cache_hits"],
            "index_references": scale["index_reference_count"],
        },
        "queries": scale["queries"],
        "mcp": mcp,
        "indexing": indexing,
        "retention": retention,
        "backend": backend,
    }
    args.output_json.parent.mkdir(parents=True, exist_ok=True)
    args.output_md.parent.mkdir(parents=True, exist_ok=True)
    args.output_json.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    lines = [
        "# M16 — Scalability qualification summary",
        "",
        f"- status: **{summary['status']}**",
        f"- HEAD: `{summary['head']}`",
        f"- profile: `{summary['profile']}`",
        f"- files/symbols/occurrences/relationships: `{scale['logical_file_count']}` / `{scale['symbol_count']}` / `{scale['occurrence_count']}` / `{scale['relationship_count']}`",
        f"- peak heap: `{scale['peak_heap_bytes']}` bytes",
        f"- retained heap: `{scale['retained_heap_bytes']}` bytes",
        f"- peak RSS: `{scale['process_rss_bytes']}` bytes",
        f"- snapshot disk: `{scale['snapshot_disk_size_bytes']}` bytes",
        f"- cache: full-loads=`{scale['active_snapshot_full_loads']}`, builds=`{scale['query_view_builds']}`, hits=`{scale['query_cache_hits']}`",
        f"- backend decision: `{backend['decision']}`",
        "",
        "## Query latency",
        "",
        "| query | p50 ms | p95 ms | p99 ms |",
        "|---|---:|---:|---:|",
    ]
    for name, stats in scale["queries"].items():
        lines.append(f"| {name} | {stats['p50_ms']} | {stats['p95_ms']} | {stats['p99_ms']} |")
    if failures:
        lines += ["", "## Failures", ""] + [f"- {failure}" for failure in failures]
    args.output_md.write_text("\n".join(lines) + "\n", encoding="utf-8")

    if failures:
        for failure in failures:
            print(f"M16 gate failure: {failure}")
        return 2
    print("M16 BENCHMARK RESULT GATES SUCCESS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
