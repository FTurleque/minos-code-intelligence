#!/usr/bin/env python3
"""Static and fixture gate for M21-S7 advanced provider productionization."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import literal_gate  # noqa: E402 - after the script directory is added to the path

ROOT = Path(__file__).resolve().parents[2]
SIDE = "fixtures/m21/advanced-program-sidecar/project/.minos/program-graph-v1"
METADATA = f"{SIDE}/metadata.properties"
NODES = f"{SIDE}/nodes.tsv"
EDGES = f"{SIDE}/edges.tsv"
EXPECTED_CAPABILITIES = {
    "CONTROL_FLOW",
    "LOCAL_DATA_FLOW",
    "INTERPROCEDURAL_DATA_FLOW",
    "SECURITY_TAINT",
}
EXPECTED_EDGE_KINDS = {
    "CONTROL_FLOW": 2,
    "DEF_USE": 1,
    "ARGUMENT_FLOW": 1,
    "RETURN_FLOW": 1,
    "TAINT_FLOW": 2,
}


def _replace_line(index: int, old: str, new: str):
    """A mutation that replaces ``old`` by ``new`` in the line ``index`` of a fixture."""
    def mutate(text: str) -> str:
        lines = text.split("\n")
        lines[index] = lines[index].replace(old, new)
        return "\n".join(lines)
    return mutate


# The fixture rules are structured (parsed properties and TSV rows): one mutation each makes them false.
STRUCTURED_MUTATIONS = {
    "fixture property syntax": [(METADATA, lambda text: text + "malformed property without separator\n")],
    "fixture formatVersion": [(METADATA, lambda text: text.replace("formatVersion=1", "formatVersion=2"))],
    "fixture capabilities": [(METADATA, lambda text: text.replace(",SECURITY_TAINT", ""))],
    "fixture snapshot": [(METADATA, lambda text: text.replace("snapshot-s7", "snapshot-s8"))],
    "fixture not empty": [(NODES, lambda text: "")],
    "fixture header": [(NODES, _replace_line(0, "symbolId", "symbol")), (EDGES, _replace_line(0, "kind", "type"))],
    "fixture row width": [(EDGES, _replace_line(1, "CONTROL_FLOW", "CONTROL_FLOW\textra"))],
    "fixture unique node ids": [(NODES, lambda text: text.rstrip("\n") + "\n" + text.split("\n")[1] + "\n")],
    "fixture edge endpoints": [(EDGES, _replace_line(1, "block:branch", "block:unknown"))],
    "fixture edge-kind counts": [(EDGES, _replace_line(1, "CONTROL_FLOW", "DEF_USE"))],
    "fixture node kinds": [(NODES, lambda text: text.replace("\tSINK\t", "\tSOURCE\t"))],
}


def read(relative: str) -> str:
    return literal_gate.read(relative)


def require(text: str, expected: str, label: str) -> None:
    literal_gate.require(label, text, expected, f"{label}: missing expected text: {expected}")


def forbid(text: str, forbidden: str, label: str) -> None:
    literal_gate.forbid(label, text, forbidden, f"{label}: forbidden text present: {forbidden}")


def properties(relative: str) -> dict[str, str]:
    result: dict[str, str] = {}
    for raw in read(relative).splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        literal_gate.check("fixture property syntax", "=" in line, f"{ROOT / relative}: malformed property: {raw}")
        key, value = line.split("=", 1)
        result[key.strip()] = value.strip()
    return result


def tsv(relative: str, expected_header: list[str]) -> list[dict[str, str]]:
    path = ROOT / relative
    lines = read(relative).splitlines()
    literal_gate.check("fixture not empty", bool(lines), f"{path}: empty fixture")
    header = lines[0].split("\t")
    literal_gate.check("fixture header", header == expected_header, f"{path}: unexpected header: {header}")
    rows: list[dict[str, str]] = []
    for number, raw in enumerate(lines[1:], start=2):
        if not raw.strip() or raw.startswith("#"):
            continue
        values = raw.split("\t")
        literal_gate.check("fixture row width", len(values) == len(header),
                           f"{path}:{number}: expected {len(header)} fields, got {len(values)}")
        rows.append(dict(zip(header, values)))
    return rows


def evaluate() -> str:
    """Assert the facts of the gate; return its success message."""
    provider = read("minos-application/src/main/java/com/minos/program/analysis/FileProgramGraphProvider.java")
    service = read("minos-application/src/main/java/com/minos/program/analysis/ProgramGraphService.java")
    spi = read("minos-application/src/main/java/com/minos/program/analysis/ProgramGraphProvider.java")
    ignore = read("minos-engine/src/main/java/com/minos/source/ProjectIgnoreRules.java")
    fixture_test = read("minos-application/src/test/java/com/minos/program/analysis/AdvancedProgramSidecarFixtureTest.java")

    for token in (
        'RELATIVE_DIRECTORY = ".minos/program-graph-v1"',
        '"ADVANCED_PROGRAM_SIDECAR_STALE_SNAPSHOT"',
        '"ADVANCED_PROGRAM_FACTS_PROVIDER_ASSERTED"',
        'ProgramGraphCapability.INTERPROCEDURAL_DATA_FLOW',
        'ProgramEdgeKind.ARGUMENT_FLOW',
        'ProgramEdgeKind.RETURN_FLOW',
        'ProgramGraphCapability.SECURITY_TAINT',
        'ProgramEdgeKind.TAINT_FLOW',
        'sha256(metadata, nodes, edges)',
    ):
        require(provider, token, "FileProgramGraphProvider")

    require(spi, "default String cacheKey", "ProgramGraphProvider")
    require(spi, "default CacheKeyResolution resolveCacheKey", "ProgramGraphProvider")
    require(service, "provider.resolveCacheKey(project, snapshot)", "ProgramGraphService")
    require(service, "new FileProgramGraphProvider()", "ProgramGraphService")
    forbid(service, "capabilities.add(ProgramGraphCapability.INTERPROCEDURAL_DATA_FLOW)", "ProgramGraphService")
    require(ignore, '".minos"', "ProjectIgnoreRules")
    require(ignore, 'root.resolve(".minosignore")', "ProjectIgnoreRules")
    require(fixture_test, "evaluation.perfect()", "AdvancedProgramSidecarFixtureTest")

    for file in (METADATA, NODES, EDGES):
        literal_gate.require_file(str(Path(file)), file, f"missing versioned provider fixture: {Path(file)}")

    meta = properties(METADATA)
    literal_gate.check("fixture formatVersion", meta.get("formatVersion") == "1", "fixture formatVersion must be 1")
    capabilities = {value.strip() for value in meta.get("capabilities", "").split(",") if value.strip()}
    literal_gate.check("fixture capabilities", capabilities == EXPECTED_CAPABILITIES,
                       f"fixture capabilities mismatch: {sorted(capabilities)}")
    literal_gate.check("fixture snapshot", meta.get("snapshotId") == "snapshot-s7",
                       "fixture snapshot must be snapshot-s7")

    nodes = tsv(NODES, [
        "id", "symbolId", "kind", "label", "fileId", "startLine", "startColumn",
        "endLine", "endColumn", "positionEncoding",
    ])
    edges = tsv(EDGES, ["id", "sourceNodeId", "targetNodeId", "kind"])
    node_ids = {row["id"] for row in nodes}
    literal_gate.check("fixture unique node ids", len(node_ids) == len(nodes), "fixture contains duplicate node ids")
    for row in edges:
        literal_gate.check("fixture edge endpoints",
                           row["sourceNodeId"] in node_ids and row["targetNodeId"] in node_ids,
                           f"fixture edge references unknown node: {row['id']}")

    counts: dict[str, int] = {}
    for row in edges:
        counts[row["kind"]] = counts.get(row["kind"], 0) + 1
    literal_gate.check("fixture edge-kind counts", counts == EXPECTED_EDGE_KINDS,
                       f"fixture edge-kind counts mismatch: {counts}")
    node_kinds = {row["kind"] for row in nodes}
    literal_gate.check("fixture node kinds", {"SOURCE", "SANITIZER", "SINK"}.issubset(node_kinds),
                       "fixture must contain SOURCE, SANITIZER and SINK")

    return (
        "M21 ADVANCED PROVIDER CONSISTENCY SUCCESS "
        f"(capabilities={len(capabilities)}, nodes={len(nodes)}, edges={len(edges)})"
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parser.add_argument("--self-test", action="store_true",
                        help="refuse one in-memory mutation per asserted fact, then exit")
    if parser.parse_args(argv).self_test:
        return literal_gate.self_test(ROOT, evaluate, STRUCTURED_MUTATIONS)
    return literal_gate.run(ROOT, evaluate, "M21 ADVANCED PROVIDER CONSISTENCY FAILED")


if __name__ == "__main__":
    raise SystemExit(main())
