#!/usr/bin/env python3
"""Static and fixture gate for M21-S7 advanced provider productionization."""

from __future__ import annotations

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
SIDE = ROOT / "fixtures/m21/advanced-program-sidecar/project/.minos/program-graph-v1"
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


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        raise RuntimeError(f"missing required file: {relative}")
    return path.read_text(encoding="utf-8")


def require(text: str, expected: str, label: str) -> None:
    if expected not in text:
        raise RuntimeError(f"{label}: missing expected text: {expected}")


def forbid(text: str, forbidden: str, label: str) -> None:
    if forbidden in text:
        raise RuntimeError(f"{label}: forbidden text present: {forbidden}")


def properties(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise RuntimeError(f"{path}: malformed property: {raw}")
        key, value = line.split("=", 1)
        result[key.strip()] = value.strip()
    return result


def tsv(path: Path, expected_header: list[str]) -> list[dict[str, str]]:
    lines = path.read_text(encoding="utf-8").splitlines()
    if not lines:
        raise RuntimeError(f"{path}: empty fixture")
    header = lines[0].split("\t")
    if header != expected_header:
        raise RuntimeError(f"{path}: unexpected header: {header}")
    rows: list[dict[str, str]] = []
    for number, raw in enumerate(lines[1:], start=2):
        if not raw.strip() or raw.startswith("#"):
            continue
        values = raw.split("\t")
        if len(values) != len(header):
            raise RuntimeError(f"{path}:{number}: expected {len(header)} fields, got {len(values)}")
        rows.append(dict(zip(header, values)))
    return rows


def main() -> int:
    try:
        provider = read("minos-application/src/main/java/com/minos/program/analysis/FileProgramGraphProvider.java")
        service = read("minos-application/src/main/java/com/minos/program/analysis/ProgramGraphService.java")
        spi = read("minos-application/src/main/java/com/minos/program/analysis/ProgramGraphProvider.java")
        ignore = read("minos-application/src/main/java/com/minos/discovery/ProjectIgnorePolicy.java")
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
        require(service, "provider.cacheKey(project, snapshot)", "ProgramGraphService")
        require(service, "new FileProgramGraphProvider()", "ProgramGraphService")
        forbid(service, "capabilities.add(ProgramGraphCapability.INTERPROCEDURAL_DATA_FLOW)", "ProgramGraphService")
        require(ignore, '".minos"', "ProjectIgnorePolicy")
        require(fixture_test, "evaluation.perfect()", "AdvancedProgramSidecarFixtureTest")

        metadata_file = SIDE / "metadata.properties"
        nodes_file = SIDE / "nodes.tsv"
        edges_file = SIDE / "edges.tsv"
        for file in (metadata_file, nodes_file, edges_file):
            if not file.is_file():
                raise RuntimeError(f"missing versioned provider fixture: {file.relative_to(ROOT)}")

        meta = properties(metadata_file)
        if meta.get("formatVersion") != "1":
            raise RuntimeError("fixture formatVersion must be 1")
        capabilities = {value.strip() for value in meta.get("capabilities", "").split(",") if value.strip()}
        if capabilities != EXPECTED_CAPABILITIES:
            raise RuntimeError(f"fixture capabilities mismatch: {sorted(capabilities)}")
        if meta.get("snapshotId") != "snapshot-s7":
            raise RuntimeError("fixture snapshot must be snapshot-s7")

        nodes = tsv(nodes_file, [
            "id", "symbolId", "kind", "label", "fileId", "startLine", "startColumn",
            "endLine", "endColumn", "positionEncoding",
        ])
        edges = tsv(edges_file, ["id", "sourceNodeId", "targetNodeId", "kind"])
        node_ids = {row["id"] for row in nodes}
        if len(node_ids) != len(nodes):
            raise RuntimeError("fixture contains duplicate node ids")
        for row in edges:
            if row["sourceNodeId"] not in node_ids or row["targetNodeId"] not in node_ids:
                raise RuntimeError(f"fixture edge references unknown node: {row['id']}")

        counts: dict[str, int] = {}
        for row in edges:
            counts[row["kind"]] = counts.get(row["kind"], 0) + 1
        if counts != EXPECTED_EDGE_KINDS:
            raise RuntimeError(f"fixture edge-kind counts mismatch: {counts}")
        node_kinds = {row["kind"] for row in nodes}
        if not {"SOURCE", "SANITIZER", "SINK"}.issubset(node_kinds):
            raise RuntimeError("fixture must contain SOURCE, SANITIZER and SINK")

        print(
            "M21 ADVANCED PROVIDER CONSISTENCY SUCCESS "
            f"(capabilities={len(capabilities)}, nodes={len(nodes)}, edges={len(edges)})"
        )
        return 0
    except Exception as exception:
        print(f"M21 ADVANCED PROVIDER CONSISTENCY FAILED: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
