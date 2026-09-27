#!/usr/bin/env python3
"""Experimental SQLite comparison for M16. Never imported by MINOS runtime."""

from __future__ import annotations

import argparse
import json
import math
import os
import sqlite3
import statistics
import time
from pathlib import Path

PROFILES = {
    "SMOKE": (1_000, 10_000, 50_000, 20_000),
    "STANDARD": (10_000, 100_000, 500_000, 250_000),
    "EXTENDED": (50_000, 1_000_000, 5_000_000, 2_000_000),
    "STRESS": (100_000, 1_000_000, 10_000_000, 4_000_000),
}


def symbol_id(index: int) -> str:
    return f"sym-{index:09d}"


def symbol_group(index: int) -> str:
    return f"SymbolGroup{index % 1000:04d}"


def file_id(index: int) -> str:
    return f"src/main/java/bench/F{index:06d}.java"


def percentile(samples: list[float], q: float) -> float:
    ordered = sorted(samples)
    return ordered[math.floor((len(ordered) - 1) * q)]


def measure(connection: sqlite3.Connection, sql: str, params: tuple[object, ...], repetitions: int) -> dict[str, float]:
    for _ in range(5):
        connection.execute(sql, params).fetchall()
    samples: list[float] = []
    for _ in range(repetitions):
        started = time.perf_counter_ns()
        connection.execute(sql, params).fetchall()
        samples.append((time.perf_counter_ns() - started) / 1_000_000.0)
    return {
        "p50_ms": round(percentile(samples, 0.50), 4),
        "p95_ms": round(percentile(samples, 0.95), 4),
        "p99_ms": round(percentile(samples, 0.99), 4),
        "average_ms": round(statistics.fmean(samples), 4),
    }


def batched_rows(total: int, factory, batch_size: int = 20_000):
    batch = []
    for index in range(total):
        batch.append(factory(index))
        if len(batch) >= batch_size:
            yield batch
            batch = []
    if batch:
        yield batch


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--profile", choices=PROFILES, default="STANDARD")
    parser.add_argument("--repetitions", type=int, default=30)
    parser.add_argument("--database", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not 5 <= args.repetitions <= 500:
        raise SystemExit("repetitions must be between 5 and 500")

    files, symbols, occurrences, relationships = PROFILES[args.profile]
    args.database.parent.mkdir(parents=True, exist_ok=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.database.exists():
        args.database.unlink()

    started = time.perf_counter_ns()
    connection = sqlite3.connect(args.database)
    try:
        connection.execute("PRAGMA journal_mode=WAL")
        connection.execute("PRAGMA synchronous=NORMAL")
        connection.executescript(
            """
            CREATE TABLE symbols (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                qualified_name TEXT NOT NULL,
                file_id TEXT NOT NULL
            );
            CREATE TABLE occurrences (
                id INTEGER PRIMARY KEY,
                symbol_id TEXT NOT NULL,
                file_id TEXT NOT NULL
            );
            CREATE TABLE relationships (
                id INTEGER PRIMARY KEY,
                source_id TEXT NOT NULL,
                target_id TEXT NOT NULL,
                kind TEXT NOT NULL
            );
            """
        )
        for batch in batched_rows(
            symbols,
            lambda i: (symbol_id(i), symbol_group(i), f"bench.{symbol_id(i)}", file_id(i % files)),
        ):
            connection.executemany("INSERT INTO symbols VALUES (?,?,?,?)", batch)
        for batch in batched_rows(
            occurrences,
            lambda i: (i, symbol_id(i % symbols), file_id((i * 31) % files)),
        ):
            connection.executemany("INSERT INTO occurrences VALUES (?,?,?)", batch)
        def relationship_row(i: int):
            source = i % symbols
            target = (source + 1) % symbols
            kind = "RELATED_TEST" if source % 10 == 0 else ("DEPENDS_ON" if source % 10 in (2, 3) else "CALLS")
            return i, symbol_id(source), symbol_id(target), kind
        for batch in batched_rows(relationships, relationship_row):
            connection.executemany("INSERT INTO relationships VALUES (?,?,?,?)", batch)
        connection.executescript(
            """
            CREATE INDEX idx_symbols_name ON symbols(name);
            CREATE INDEX idx_symbols_qualified ON symbols(qualified_name);
            CREATE INDEX idx_symbols_file ON symbols(file_id);
            CREATE INDEX idx_occurrences_symbol ON occurrences(symbol_id);
            CREATE INDEX idx_relationships_source_kind ON relationships(source_id, kind);
            CREATE INDEX idx_relationships_target_kind ON relationships(target_id, kind);
            """
        )
        connection.commit()
        connection.execute("PRAGMA wal_checkpoint(TRUNCATE)").fetchall()
        build_ms = (time.perf_counter_ns() - started) / 1_000_000.0

        mid = symbols // 2
        group = symbol_group(mid)
        usage_anchor = symbol_id(mid + 3)
        dependency_anchor = symbol_id(mid + 2)
        dependent_anchor = symbol_id(mid + 3)
        related_anchor = symbol_id(mid + 1)

        queries = {
            "find-symbol": measure(connection, "SELECT id,name,qualified_name,file_id FROM symbols WHERE name=? ORDER BY qualified_name,id LIMIT 20", (group,), args.repetitions),
            "find-usages": measure(connection, "SELECT id,file_id FROM occurrences WHERE symbol_id=? ORDER BY file_id,id LIMIT 20", (usage_anchor,), args.repetitions),
            "dependencies": measure(connection, "SELECT id,target_id FROM relationships WHERE source_id=? AND kind='DEPENDS_ON' ORDER BY target_id,id LIMIT 20", (dependency_anchor,), args.repetitions),
            "dependents": measure(connection, "SELECT id,source_id FROM relationships WHERE target_id=? AND kind='DEPENDS_ON' ORDER BY source_id,id LIMIT 20", (dependent_anchor,), args.repetitions),
            "related-tests": measure(connection, "SELECT id,source_id,target_id FROM relationships WHERE (source_id=? OR target_id=?) AND kind='RELATED_TEST' ORDER BY id LIMIT 20", (related_anchor, related_anchor), args.repetitions),
            "search": measure(connection, "SELECT id,name,qualified_name,file_id FROM symbols WHERE name=? ORDER BY qualified_name,id LIMIT 5", (group,), args.repetitions),
        }
    finally:
        connection.close()

    disk = sum(
        path.stat().st_size
        for path in args.database.parent.glob(args.database.name + "*")
        if path.is_file()
    )
    result = {
        "backend": "sqlite-experimental",
        "profile": args.profile,
        "logical_file_count": files,
        "symbol_count": symbols,
        "occurrence_count": occurrences,
        "relationship_count": relationships,
        "build_time_ms": round(build_ms, 4),
        "disk_size_bytes": disk,
        "queries": queries,
        "runtime_dependency_added": False,
    }
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(
        f"M16 SQLite comparison: profile={args.profile} build={build_ms:.3f}ms disk={disk} "
        f"find-symbol-p95={queries['find-symbol']['p95_ms']:.3f}ms"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
