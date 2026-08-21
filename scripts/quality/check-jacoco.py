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
        ),
        "line": 0.50,
        "branch": 0.28,
        "prefixMinimums": {
            "com/minos/adapter/scip/runtime/ManagedPolyglotScipRuntimeManager": {"line": 0.25, "branch": 0.15},
        },
    },
    "m25-remote-distributed-indexing": {
        "prefixes": (
            "com/minos/remote/", "com/minos/git/JGitRemoteRepositoryMaterializer", "com/minos/git/JGitCloneDeadline",
            "com/minos/git/RemoteRepositoryCachePolicy", "com/minos/runtime/DistributedArtifactBundleStore",
            "com/minos/runtime/DistributedArtifactCachePolicy", "com/minos/runtime/DistributedIndexerExecutor",
            "com/minos/runtime/LocalIsolatedIndexWorker", "com/minos/runtime/WorkerSandboxBackend",
            "com/minos/runtime/WorkerSandboxQualification", "com/minos/cli/LocalRemoteIndexOperations",
            "com/minos/cli/RemoteIndexCommand",
        ),
        "line": 0.70,
        "branch": 0.50,
        "prefixMinimums": {
            "com/minos/git/JGitCloneDeadline": {"line": 0.40, "branch": 0.30},
        },
    },
    "provider-execution-trust-boundary": {
        "prefixes": (
            "com/minos/runtime/ProcessIndexerExecutor", "com/minos/runtime/StrongProcessOwnershipIndexerExecutor",
            "com/minos/runtime/LocalProviderWorkspace", "com/minos/runtime/ProviderWorkspaceFiles",
            "com/minos/runtime/WorkerSandboxBackend", "com/minos/runtime/WorkerSandboxQualification",
            "com/minos/runtime/ProviderProcessEnvironment", "com/minos/runtime/ProcessTreeTermination",
            "com/minos/runtime/ProviderResidueReclamation",
        ),
        "line": 0.68,
        "branch": 0.48,
        "prefixMinimums": {
            "com/minos/runtime/StrongProcessOwnershipIndexerExecutor": {"line": 0.50, "branch": 0.20},
            "com/minos/runtime/ProviderProcessEnvironment": {"line": 0.60, "branch": 0.15},
        },
    },
    "provider-sandbox-linux": {
        "prefixes": (
            "com/minos/runtime/LinuxBubblewrapWorkerSandboxBackend",
            "com/minos/runtime/LinuxCgroupJob",
        ),
        "platform": "linux",
        "line": 0.55,
        "branch": 0.35,
    },
    "provider-sandbox-windows": {
        "prefixes": (
            "com/minos/runtime/WindowsAppContainerWorkerSandboxBackend",
            "com/minos/runtime/WindowsContainmentScript",
        ),
        "platform": "windows",
        "line": 0.55,
        "branch": 0.35,
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
    "nexus-export": {"prefixes": ("com/minos/integration/nexus/",), "line": 0.20, "branch": 0.08},
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


def current_platform() -> str:
    if sys.platform.startswith("win"):
        return "windows"
    if sys.platform.startswith("linux"):
        return "linux"
    return "other"


def self_test() -> int:
    """Exercises the gate's own decision logic on synthetic reports.

    The gate is the only thing standing between a renamed class and a silently shrinking coverage
    surface, so its behaviour is verified here rather than assumed. Runs without pytest so it can be
    invoked from any CI step.
    """
    import tempfile

    report_xml = """<?xml version="1.0" encoding="UTF-8"?>
<report name="self-test">
  <class name="com/minos/selftest/Alpha">
    <counter type="LINE" covered="90" missed="10"/>
    <counter type="BRANCH" covered="80" missed="20"/>
  </class>
  <class name="com/minos/selftest/Weak">
    <counter type="LINE" covered="10" missed="90"/>
    <counter type="BRANCH" covered="10" missed="90"/>
  </class>
  <class name="com/minos/selftest/pkg/Beta">
    <counter type="LINE" covered="90" missed="10"/>
    <counter type="BRANCH" covered="80" missed="20"/>
  </class>
</report>
"""
    failures: list[str] = []

    def run(scopes: dict, *, skip: list[str] | None = None) -> tuple[int, list[str]]:
        original = dict(SCOPES)
        SCOPES.clear()
        SCOPES.update(scopes)
        try:
            with tempfile.TemporaryDirectory() as directory:
                report = Path(directory) / "jacoco.xml"
                report.write_text(report_xml, encoding="utf-8")
                output = Path(directory) / "gate.json"
                argv = [sys.argv[0], str(report), "--output", str(output)]
                for name in skip or []:
                    argv += ["--skip-scope", name]
                saved_argv = sys.argv
                sys.argv = argv
                try:
                    code = main()
                finally:
                    sys.argv = saved_argv
                payload = json.loads(output.read_text(encoding="utf-8"))
                return code, [n for n, r in payload["scopes"].items() if r.get("status") == "FAIL"]
        finally:
            SCOPES.clear()
            SCOPES.update(original)

    def expect(label: str, actual, wanted) -> None:
        if actual != wanted:
            failures.append(f"{label}: expected {wanted!r}, got {actual!r}")

    live_class = {"prefixes": ("com/minos/selftest/Alpha",), "line": 0.5, "branch": 0.5}
    live_package = {"prefixes": ("com/minos/selftest/pkg/",), "line": 0.5, "branch": 0.5}

    # 1. every declared prefix valid (class prefix and package prefix) -> PASS
    code, failed = run({"class-prefix": live_class, "package-prefix": live_package})
    expect("all prefixes valid: exit", code, 0)
    expect("all prefixes valid: failures", failed, [])

    # 2. one dead prefix beside a live one -> FAIL (the regression this gate previously missed)
    code, failed = run({"mixed": {
        "prefixes": ("com/minos/selftest/Alpha", "com/minos/selftest/Removed"),
        "line": 0.5, "branch": 0.5,
    }})
    expect("dead prefix beside live: exit", code, 1)
    expect("dead prefix beside live: failures", failed, ["mixed"])

    # 3. a dead package prefix is caught the same way
    code, failed = run({"dead-package": {
        "prefixes": ("com/minos/selftest/Alpha", "com/minos/selftest/gone/"),
        "line": 0.5, "branch": 0.5,
    }})
    expect("dead package prefix: exit", code, 1)
    expect("dead package prefix: failures", failed, ["dead-package"])

    # 4. no prefix matches anything -> FAIL
    code, failed = run({"empty": {"prefixes": ("com/minos/absent/",), "line": 0.5, "branch": 0.5}})
    expect("no prefix matches: exit", code, 1)
    expect("no prefix matches: failures", failed, ["empty"])

    # 5. an unmet threshold still fails, independently of prefix validity
    code, failed = run({"threshold": {"prefixes": ("com/minos/selftest/Alpha",), "line": 0.99, "branch": 0.5}})
    expect("threshold breach: exit", code, 1)
    expect("threshold breach: failures", failed, ["threshold"])

    # 6. platform exclusion keeps SKIPPED semantics: a dead prefix in an inapplicable scope is not
    #    evaluated at all, exactly as before.
    other_platform = "windows" if current_platform() != "windows" else "linux"
    code, failed = run({"platform-excluded": {
        "prefixes": ("com/minos/selftest/Removed",), "platform": other_platform, "line": 0.5, "branch": 0.5,
    }})
    expect("platform exclusion: exit", code, 0)
    expect("platform exclusion: failures", failed, [])

    # 7. explicit --skip-scope keeps SKIPPED semantics for the same case
    code, failed = run(
        {"skippable": {"prefixes": ("com/minos/selftest/Removed",), "line": 0.5, "branch": 0.5}},
        skip=["skippable"],
    )
    expect("explicit skip: exit", code, 0)
    expect("explicit skip: failures", failed, [])

    # 8. a weak live prefix cannot hide behind a strongly-covered sibling when the aggregate passes.
    code, failed = run({"prefix-floor": {
        "prefixes": ("com/minos/selftest/Alpha", "com/minos/selftest/Weak"),
        "line": 0.50,
        "branch": 0.45,
        "prefixMinimums": {
            "com/minos/selftest/Weak": {"line": 0.50, "branch": 0.50},
        },
    }})
    expect("weak live prefix: exit", code, 1)
    expect("weak live prefix: failures", failed, ["prefix-floor"])

    if failures:
        print("MINOS JACOCO GATE SELF-TEST FAILED", file=sys.stderr)
        for failure in failures:
            print(f" - {failure}", file=sys.stderr)
        return 1
    print("MINOS JACOCO GATE SELF-TEST SUCCESS (8 scenarios)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("xml", nargs="?", default="target/site/jacoco-aggregate/jacoco.xml", help="JaCoCo aggregate XML report")
    parser.add_argument("--output", default="target/m21-quality/jacoco-gate.json", help="machine-readable gate result")
    parser.add_argument(
        "--skip-scope", action="append", default=[], choices=sorted(SCOPES),
        help="Skip one environment-inapplicable scope. May be repeated.",
    )
    parser.add_argument(
        "--self-test", action="store_true",
        help="Verify the gate's own decision logic on synthetic reports and exit.",
    )
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    default_report = Path(args.xml)
    if not default_report.is_file():
        print(f"ERROR: JaCoCo aggregate report not found: {default_report}", file=sys.stderr)
        return 2

    skipped = set(args.skip_scope)
    platform = current_platform()
    cache: dict[Path, list[ET.Element]] = {}
    results: dict[str, object] = {"report": str(default_report), "platform": platform, "scopes": {}}
    failures: list[str] = []

    for name, config in SCOPES.items():
        required_platform = config.get("platform")
        if name in skipped or (required_platform is not None and required_platform != platform):
            reason = "explicit environment-specific exclusion" if name in skipped else f"platform={platform}"
            results["scopes"][name] = {"classes": 0, "status": "SKIPPED", "reason": reason}
            print(f"JaCoCo {name}: SKIPPED ({reason})")
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

        # Every declared prefix must still designate real code. Without this, a renamed or deleted
        # class silently stops being measured as soon as any sibling prefix in the same scope keeps
        # matching, and the scope keeps reporting PASS over a shrinking surface.
        prefix_matches = {
            prefix: [clazz for clazz in all_classes if clazz.attrib.get("name", "").startswith(prefix)]
            for prefix in prefixes
        }
        dead = [prefix for prefix, matches in prefix_matches.items() if not matches]
        if dead:
            failures.append(
                f"{name}: declared prefix(es) match no class in {report} "
                f"(renamed or deleted?): {', '.join(sorted(dead))}"
            )
            results["scopes"][name] = {
                "classes": len(classes), "report": str(report), "status": "FAIL", "deadPrefixes": sorted(dead),
            }
            continue

        counters = counters_for(classes)
        line = ratio(*counters["LINE"])
        branch = ratio(*counters["BRANCH"])
        line_min = float(config["line"])
        branch_min = float(config["branch"])
        passed = line >= line_min and branch >= branch_min
        if not passed:
            failures.append(f"{name}: line={line:.3f} (min {line_min:.3f}), branch={branch:.3f} (min {branch_min:.3f})")

        prefix_results: dict[str, object] = {}
        for prefix, minimums in config.get("prefixMinimums", {}).items():
            if prefix not in prefix_matches:
                failures.append(f"{name}: prefix minimum references undeclared prefix: {prefix}")
                passed = False
                prefix_results[prefix] = {"status": "FAIL", "reason": "undeclared prefix"}
                continue
            prefix_counters = counters_for(prefix_matches[prefix])
            prefix_line = ratio(*prefix_counters["LINE"])
            prefix_branch = ratio(*prefix_counters["BRANCH"])
            prefix_line_min = float(minimums["line"])
            prefix_branch_min = float(minimums["branch"])
            prefix_passed = prefix_line >= prefix_line_min and prefix_branch >= prefix_branch_min
            if not prefix_passed:
                failures.append(
                    f"{name}: prefix {prefix} line={prefix_line:.3f} (min {prefix_line_min:.3f}), "
                    f"branch={prefix_branch:.3f} (min {prefix_branch_min:.3f})"
                )
                passed = False
            prefix_results[prefix] = {
                "classes": len(prefix_matches[prefix]),
                "line": round(prefix_line, 6),
                "lineMinimum": prefix_line_min,
                "branch": round(prefix_branch, 6),
                "branchMinimum": prefix_branch_min,
                "status": "PASS" if prefix_passed else "FAIL",
            }

        result = {
            "classes": len(classes), "report": str(report), "line": round(line, 6), "lineMinimum": line_min,
            "branch": round(branch, 6), "branchMinimum": branch_min, "status": "PASS" if passed else "FAIL",
        }
        if prefix_results:
            result["prefixes"] = prefix_results
        results["scopes"][name] = result

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
