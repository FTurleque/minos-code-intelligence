#!/usr/bin/env python3
"""Targeted JaCoCo gates for critical MINOS responsibilities through M30."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
import xml.etree.ElementTree as ET


SCOPES = {
    "domain-invariants": {"prefixes": ("com/minos/domain/",), "line": 0.35, "branch": 0.20},
    "persistence-cache-indexes": {
        "prefixes": (
            "com/minos/store/FileSymbolSnapshotStore", "com/minos/store/ActiveSnapshotRepository",
            "com/minos/store/SnapshotCodec", "com/minos/store/SnapshotIntegrityService",
            "com/minos/store/InMemoryCodeKnowledgeStore", "com/minos/store/SnapshotQueryView",
        ), "line": 0.50, "branch": 0.35,
    },
    "project-resolution": {"prefixes": ("com/minos/application/ProjectResolver",), "line": 0.70, "branch": 0.50},
    "public-api": {"prefixes": ("com/minos/api/",), "line": 0.60, "branch": 0.35},
    "mcp-mapping": {"prefixes": ("com/minos/mcp/",), "line": 0.55, "branch": 0.40},
    "program-graph-analysis": {
        "prefixes": (
            "com/minos/program/analysis/ProgramGraphService", "com/minos/program/analysis/ProgramGraphComposer",
            "com/minos/program/analysis/RelationshipProgramGraphProvider", "com/minos/program/analysis/FileProgramGraphProvider",
            "com/minos/program/analysis/ProgramGraphEvaluator", "com/minos/program/analysis/InterproceduralFlowService",
        ), "line": 0.50, "branch": 0.30,
    },
    "java-advanced-provider": {
        "prefixes": (
            "com/minos/program/analysis/JavaSourceProgramGraphProvider", "com/minos/program/analysis/JavaSourceWorkspace",
            "com/minos/program/analysis/JavaAstParser", "com/minos/program/analysis/JavaAstSupport",
            "com/minos/program/analysis/JavaProgramModel", "com/minos/program/analysis/JavaSecurityRules",
            "com/minos/program/analysis/JavaProgramGraphContext", "com/minos/program/analysis/JavaDefUseAnalyzer",
            "com/minos/program/analysis/JavaControlFlowAnalyzer", "com/minos/program/analysis/JavaInterproceduralFlowResolver",
            "com/minos/program/analysis/JavaTaintAnalyzer", "com/minos/program/analysis/JavaProgramGraphAssembler",
            "com/minos/program/analysis/JavaProgramGraphEngine", "com/minos/program/analysis/FingerprintConstrainedJavaProgramGraphProvider",
        ), "line": 0.45, "branch": 0.25,
    },
    "advanced-impact-security": {
        "prefixes": ("com/minos/program/analysis/AdvancedImpactService", "com/minos/program/analysis/SecurityAnalysisService"),
        "line": 0.47, "branch": 0.27,
    },
    "semantic-vector-store": {"prefixes": ("com/minos/store/FileSemanticVectorStore",), "line": 0.45, "branch": 0.20},
    "semantic-learned-provider": {"prefixes": ("com/minos/semantic/OllamaEmbeddingProvider",), "line": 0.52, "branch": 0.32},
    "semantic-hybrid-retrieval": {
        "prefixes": (
            "com/minos/semantic/SemanticDocumentFactory", "com/minos/semantic/SemanticIndexService",
            "com/minos/semantic/SemanticSearchService", "com/minos/semantic/HybridSearchService",
            "com/minos/semantic/HybridContextBuilder", "com/minos/semantic/SemanticSearchEvaluator",
        ), "line": 0.50, "branch": 0.30,
    },
    "advanced-public-api": {
        "prefixes": (
            "com/minos/api/AdvancedCodeIntelligenceApi", "com/minos/api/LocalAdvancedCodeIntelligenceApi",
            "com/minos/api/SemanticCodeIntelligenceApi", "com/minos/api/LocalSemanticCodeIntelligenceApi",
        ), "line": 0.45, "branch": 0.25,
    },
    "m19-m20-mcp-catalogue": {"prefixes": ("com/minos/mcp/MinosMcpTools",), "line": 0.50, "branch": 0.30},
    "m24-polyglot-provider-platform": {
        "prefixes": (
            "com/minos/orchestration/ProviderConformanceKit", "com/minos/orchestration/ProviderOperationalProfile",
            "com/minos/adapter/scip/ScipIndexerCatalog", "com/minos/adapter/scip/runtime/ManagedPolyglotScipRuntimeManager",
            "com/minos/adapter/scip/runtime/ScipClangProcessPlanFactory", "com/minos/adapter/scip/runtime/ScipDotnetProcessPlanFactory",
            "com/minos/adapter/scip/runtime/ScipGoProcessPlanFactory", "com/minos/adapter/scip/runtime/RustAnalyzerScipProcessPlanFactory",
        ), "line": 0.50, "branch": 0.28,
    },
    "m25-remote-distributed-indexing": {
        "prefixes": (
            "com/minos/remote/", "com/minos/git/JGitRemoteRepositoryMaterializer", "com/minos/git/JGitCloneDeadline",
            "com/minos/git/RemoteRepositoryCachePolicy", "com/minos/runtime/DistributedArtifactBundleStore",
            "com/minos/runtime/DistributedArtifactCachePolicy", "com/minos/runtime/DistributedIndexerExecutor",
            "com/minos/runtime/LocalIsolatedIndexWorker", "com/minos/runtime/WorkerSandboxBackend",
            "com/minos/runtime/WorkerSandboxQualification", "com/minos/cli/LocalRemoteIndexOperations",
            "com/minos/cli/RemoteIndexCommand",
        ), "line": 0.70, "branch": 0.50,
    },
    "provider-execution-trust-boundary": {
        "prefixes": (
            "com/minos/runtime/ProcessIndexerExecutor", "com/minos/runtime/StrongProcessOwnershipIndexerExecutor",
            "com/minos/runtime/LocalProviderWorkspace", "com/minos/runtime/ProviderWorkspaceFiles",
            "com/minos/runtime/WorkerSandboxBackend", "com/minos/runtime/WorkerSandboxQualification",
            "com/minos/runtime/ProviderProcessEnvironment", "com/minos/runtime/ProcessTreeTermination",
            "com/minos/runtime/ProviderResidueReclamation",
        ), "line": 0.68, "branch": 0.48,
    },
    "provider-execution-linux-sandbox": {
        "prefixes": ("com/minos/runtime/LinuxBubblewrapWorkerSandboxBackend",),
        "line": 0.80, "branch": 0.65,
    },
    "provider-execution-windows-sandbox": {
        "prefixes": ("com/minos/runtime/WindowsAppContainerWorkerSandboxBackend",),
        "line": 0.80, "branch": 0.50,
    },
    "m26-runtime-dynamic-intelligence": {
        "prefixes": ("com/minos/dynamic/", "com/minos/store/FileRuntimeObservationStore", "com/minos/cli/RuntimeCommand", "com/minos/output/RuntimeIntelligenceRenderer"),
        "line": 0.55, "branch": 0.35,
    },
    "m27-team-hosted-control-plane": {
        "prefixes": (
            "com/minos/hosted/", "com/minos/store/FileHostedControlPlaneStore", "com/minos/store/EnvironmentHostedTenantKeyProvider",
            "com/minos/cli/TeamCommand", "com/minos/api/LocalMinosTeamApi", "com/minos/output/HostedControlPlaneRenderer",
        ), "line": 0.45, "branch": 0.25,
    },
    "m29-backend-routing": {
        "prefixes": (
            "com/minos/cli/McpBackend", "com/minos/cli/McpBackendConfiguration", "com/minos/cli/McpBackendConfigurationStore",
            "com/minos/cli/McpBackendRouter", "com/minos/cli/DockerRuntimeBootstrap",
        ),
        "report": "target/site/jacoco/jacoco.xml",
        "line": 0.55, "branch": 0.30,
    },
    "m30-storage-backend-selection": {
        "prefixes": (
            "com/minos/storage/StorageBackend", "com/minos/storage/StorageBackendConfiguration", "com/minos/storage/StorageBackendProvider",
            "com/minos/storage/StorageBackends", "com/minos/storage/LocalStorageBackend", "com/minos/storage/MinosRuntimeSettings",
        ), "line": 0.52, "branch": 0.32,
    },
    "m30-postgresql-pgvector": {"prefixes": ("com/minos/storage/postgresql/",), "line": 0.60, "branch": 0.40},
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


def load_classes(report: Path, cache: dict[Path, list[ET.Element]]) -> list[ET.Element]:
    if report not in cache:
        if not report.is_file():
            raise FileNotFoundError(report)
        cache[report] = ET.parse(report).getroot().findall(".//class")
    return cache[report]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("xml", nargs="?", default="target/site/jacoco-aggregate/jacoco.xml", help="JaCoCo aggregate XML report")
    parser.add_argument("--output", default="target/m21-quality/jacoco-gate.json", help="machine-readable gate result")
    parser.add_argument(
        "--skip-scope", action="append", default=[], choices=sorted(SCOPES),
        help="Skip one environment-inapplicable scope. May be repeated.",
    )
    args = parser.parse_args()

    default_report = Path(args.xml)
    if not default_report.is_file():
        print(f"ERROR: JaCoCo aggregate report not found: {default_report}", file=sys.stderr)
        return 2

    skipped = set(args.skip_scope)
    cache: dict[Path, list[ET.Element]] = {}
    results: dict[str, object] = {"report": str(default_report), "scopes": {}}
    failures: list[str] = []

    for name, config in SCOPES.items():
        if name in skipped:
            results["scopes"][name] = {"classes": 0, "status": "SKIPPED", "reason": "explicit environment-specific exclusion"}
            print(f"JaCoCo {name}: SKIPPED (explicit environment-specific exclusion)")
            continue

        report = Path(str(config.get("report", default_report)))
        try:
            all_classes = load_classes(report, cache)
        except FileNotFoundError:
            failures.append(f"{name}: JaCoCo report not found: {report}")
            results["scopes"][name] = {"classes": 0, "report": str(report), "status": "FAIL"}
            continue

        prefixes = tuple(config["prefixes"])
        classes = [clazz for clazz in all_classes if clazz.attrib.get("name", "").startswith(prefixes)]
        if not classes:
            failures.append(f"{name}: no classes matched {prefixes} in {report}")
            results["scopes"][name] = {"classes": 0, "report": str(report), "status": "FAIL"}
            continue

        counters = counters_for(classes)
        line = ratio(*counters["LINE"])
        branch = ratio(*counters["BRANCH"])
        line_min = float(config["line"])
        branch_min = float(config["branch"])
        passed = line >= line_min and branch >= branch_min
        if not passed:
            failures.append(f"{name}: line={line:.3f} (min {line_min:.3f}), branch={branch:.3f} (min {branch_min:.3f})")
        results["scopes"][name] = {
            "classes": len(classes), "report": str(report), "line": round(line, 6), "lineMinimum": line_min,
            "branch": round(branch, 6), "branchMinimum": branch_min, "status": "PASS" if passed else "FAIL",
        }

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")

    for name, result in results["scopes"].items():
        if result.get("status") == "SKIPPED":
            continue
        print(
            f"JaCoCo {name}: {result.get('status')} line={result.get('line', 'n/a')} "
            f"branch={result.get('branch', 'n/a')} classes={result.get('classes')} report={result.get('report')}"
        )

    if failures:
        print("MINOS JACOCO GATE FAILED", file=sys.stderr)
        for failure in failures:
            print(f" - {failure}", file=sys.stderr)
        return 1

    print("MINOS JACOCO GATE SUCCESS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
