#!/usr/bin/env python3
"""Targeted JaCoCo gates for critical MINOS responsibilities through M22."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
import xml.etree.ElementTree as ET


SCOPES = {
    "domain-invariants": {
        "prefixes": ("com/minos/domain/",),
        "line": 0.35,
        "branch": 0.20,
    },
    "persistence-cache-indexes": {
        "prefixes": (
            "com/minos/store/FileSymbolSnapshotStore",
            "com/minos/store/ActiveSnapshotRepository",
            "com/minos/store/SnapshotCodec",
            "com/minos/store/SnapshotIntegrityService",
            "com/minos/store/InMemoryCodeKnowledgeStore",
            "com/minos/store/SnapshotQueryView",
        ),
        "line": 0.50,
        "branch": 0.35,
    },
    "project-resolution": {
        "prefixes": ("com/minos/application/ProjectResolver",),
        "line": 0.70,
        "branch": 0.50,
    },
    "public-api": {
        "prefixes": ("com/minos/api/",),
        "line": 0.30,
        "branch": 0.20,
    },
    "mcp-mapping": {
        "prefixes": ("com/minos/mcp/",),
        "line": 0.30,
        "branch": 0.20,
    },
    "program-graph-analysis": {
        "prefixes": (
            "com/minos/program/analysis/ProgramGraphService",
            "com/minos/program/analysis/ProgramGraphComposer",
            "com/minos/program/analysis/RelationshipProgramGraphProvider",
            "com/minos/program/analysis/FileProgramGraphProvider",
            "com/minos/program/analysis/ProgramGraphEvaluator",
            "com/minos/program/analysis/InterproceduralFlowService",
        ),
        "line": 0.50,
        "branch": 0.30,
    },
    "java-advanced-provider": {
        "prefixes": ("com/minos/program/analysis/JavaSourceProgramGraphProvider",),
        "line": 0.45,
        "branch": 0.25,
    },
    "advanced-impact-security": {
        "prefixes": (
            "com/minos/program/analysis/AdvancedImpactService",
            "com/minos/program/analysis/SecurityAnalysisService",
        ),
        "line": 0.45,
        "branch": 0.25,
    },
    "semantic-vector-store": {
        "prefixes": ("com/minos/store/FileSemanticVectorStore",),
        "line": 0.45,
        "branch": 0.20,
    },
    "semantic-hybrid-retrieval": {
        "prefixes": (
            "com/minos/semantic/SemanticDocumentFactory",
            "com/minos/semantic/SemanticIndexService",
            "com/minos/semantic/SemanticSearchService",
            "com/minos/semantic/HybridSearchService",
            "com/minos/semantic/HybridContextBuilder",
            "com/minos/semantic/SemanticSearchEvaluator",
        ),
        "line": 0.50,
        "branch": 0.30,
    },
    "advanced-public-api": {
        "prefixes": (
            "com/minos/api/AdvancedCodeIntelligenceApi",
            "com/minos/api/LocalAdvancedCodeIntelligenceApi",
            "com/minos/api/SemanticCodeIntelligenceApi",
            "com/minos/api/LocalSemanticCodeIntelligenceApi",
        ),
        "line": 0.45,
        "branch": 0.25,
    },
    "m19-m20-mcp-catalogue": {
        "prefixes": ("com/minos/mcp/MinosMcpTools",),
        "line": 0.50,
        "branch": 0.30,
    },
}


def ratio(covered: int, missed: int) -> float:
    total = covered + missed
    return 1.0 if total == 0 else covered / total


def counters_for(classes: list[ET.Element]) -> dict[str, tuple[int, int]]:
    totals = {"LINE": [0, 0], "BRANCH": [0, 0]}
    for clazz in classes:
        for counter in clazz.findall("counter"):
            kind = counter.attrib.get("type")
            if kind not in totals:
                continue
            totals[kind][0] += int(counter.attrib.get("covered", "0"))
            totals[kind][1] += int(counter.attrib.get("missed", "0"))
    return {kind: (value[0], value[1]) for kind, value in totals.items()}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "xml",
        nargs="?",
        default="target/site/jacoco-aggregate/jacoco.xml",
        help="JaCoCo aggregate XML report",
    )
    parser.add_argument(
        "--output",
        default="target/m21-quality/jacoco-gate.json",
        help="machine-readable gate result",
    )
    args = parser.parse_args()

    report = Path(args.xml)
    if not report.is_file():
        print(f"ERROR: JaCoCo aggregate report not found: {report}", file=sys.stderr)
        return 2

    root = ET.parse(report).getroot()
    all_classes = root.findall(".//class")
    results: dict[str, object] = {"report": str(report), "scopes": {}}
    failures: list[str] = []

    for name, config in SCOPES.items():
        prefixes = tuple(config["prefixes"])
        classes = [clazz for clazz in all_classes if clazz.attrib.get("name", "").startswith(prefixes)]
        if not classes:
            failures.append(f"{name}: no classes matched {prefixes}")
            results["scopes"][name] = {"classes": 0, "status": "FAIL"}
            continue

        counters = counters_for(classes)
        line = ratio(*counters["LINE"])
        branch = ratio(*counters["BRANCH"])
        line_min = float(config["line"])
        branch_min = float(config["branch"])
        passed = line >= line_min and branch >= branch_min
        if not passed:
            failures.append(
                f"{name}: line={line:.3f} (min {line_min:.3f}), "
                f"branch={branch:.3f} (min {branch_min:.3f})"
            )
        results["scopes"][name] = {
            "classes": len(classes),
            "line": round(line, 6),
            "lineMinimum": line_min,
            "branch": round(branch, 6),
            "branchMinimum": branch_min,
            "status": "PASS" if passed else "FAIL",
        }

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")

    for name, result in results["scopes"].items():
        print(
            f"JaCoCo {name}: {result.get('status')} "
            f"line={result.get('line', 'n/a')} branch={result.get('branch', 'n/a')} "
            f"classes={result.get('classes')}"
        )

    if failures:
        print("M21 JACOCO GATE FAILED", file=sys.stderr)
        for failure in failures:
            print(f" - {failure}", file=sys.stderr)
        return 1

    print("M21 JACOCO GATE SUCCESS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
