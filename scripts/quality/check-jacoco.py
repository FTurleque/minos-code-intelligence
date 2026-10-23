#!/usr/bin/env python3
"""Targeted JaCoCo gates for critical MINOS responsibilities through M30."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
import xml.etree.ElementTree as ET


SCOPES = {
    # AUD-TST-01: the whole product, floored per system. com/minos/app/ is left out by name: the aggregate report does
    # not carry minos-app's own tests (m29-backend-routing reads minos-app's report instead). Measured on the
    # pr-ci.yml runs 38719668720 (develop 83dd1fbb) and 38697957273 (develop ae7b02b0), 2026-10-15, aggregate report of each system: Linux line 0.865 / branch 0.716, Windows line 0.834 / branch 0.698 (classes of the other system at 0,
    # PostgreSQL skipped on Windows). Floor = measure minus 2 points, raised at each sprint closure, never lowered
    # without a reason written in docs/project/audits/2026-10-10/SUIVI.md.
    "global": {
        "prefixes": ("com/minos/",),
        "exclusions": ("com/minos/app/",),
        "line": {"linux": 0.84, "windows": 0.81},
        "branch": {"linux": 0.69, "windows": 0.67},
    },
    # AUD-TST-02: the binary codec and the persisted state of the local store, measured identically on both systems
    # (pr-ci.yml runs 38719668720 (develop 83dd1fbb) and 38697957273 (develop ae7b02b0), 2026-10-15, aggregate report of each system): line 0.912, branch 0.758. Floor = measure minus 2 points.
    "storage-codec-and-state": {
        "prefixes": (
            "com/minos/storage/local/store/SnapshotBinaryCodecSupport",
            "com/minos/storage/local/orchestration/FileIndexStateStore",
            "com/minos/storage/local/incremental/FileProjectFingerprintSnapshotStore",
            "com/minos/storage/local/registry/LocalProjectRegistry",
        ),
        "line": 0.89, "branch": 0.73,
    },
    # AUD-TST-02: the confined I/O primitives (34 classes with their inner ones), Linux line 0.801 / branch 0.701,
    # Windows 0.814 / 0.717 (pr-ci.yml runs 38719668720 (develop 83dd1fbb) and 38697957273 (develop ae7b02b0), 2026-10-15, aggregate report of each system). Floor = the lower measure minus 2 points. LeaseDeadline (0.812 / 0.750 on
    # Windows before it had a test of its own) is floored by class: 32/32 lines, 15/16 branches with LeaseDeadlineTest
    # alone on Windows; floor = measure minus about 5 points.
    "confined-io-primitives": {
        "prefixes": ("com/minos/io/", "com/minos/io/LeaseDeadline"),
        "line": 0.78, "branch": 0.68,
        "prefixMinimums": {
            "com/minos/io/LeaseDeadline": {"line": 0.95, "branch": 0.85},
        },
    },
    # AUD-TST-02: the retention of run directories (common code), Linux 0.913 / 0.843, Windows 0.898 / 0.843
    # (pr-ci.yml runs 38719668720 (develop 83dd1fbb) and 38697957273 (develop ae7b02b0), 2026-10-15, aggregate report of each system). Floor = the lower measure minus 2 points.
    "run-directory-retention": {
        "prefixes": ("com/minos/runtime/local/workspace/RunDirectoryRetention",),
        "line": 0.87, "branch": 0.82,
    },
    "domain-invariants": {"prefixes": ("com/minos/domain/",), "line": 0.80, "branch": 0.60},
    "persistence-cache-indexes": {
        "prefixes": (
            "com/minos/storage/local/store/FileSymbolSnapshotStore", "com/minos/storage/local/store/ActiveSnapshotRepository",
            "com/minos/storage/local/store/SnapshotCodec", "com/minos/storage/local/store/SnapshotIntegrityService",
            "com/minos/store/InMemoryCodeKnowledgeStore", "com/minos/store/SnapshotQueryView",
            "com/minos/store/SnapshotReadView", "com/minos/store/CodeKnowledgeReader",
        ), "line": 0.50, "branch": 0.35,
    },
    "project-resolution": {"prefixes": ("com/minos/application/resolution/ProjectResolver",), "line": 0.70, "branch": 0.50},
    "public-api": {"prefixes": ("com/minos/api/",), "line": 0.60, "branch": 0.35},
    "mcp-mapping": {"prefixes": ("com/minos/mcp/",), "line": 0.55, "branch": 0.40},
    "program-graph-analysis": {
        "prefixes": (
            "com/minos/program/analysis/ProgramGraphService", "com/minos/program/analysis/ProgramGraphComposer",
            "com/minos/program/analysis/RelationshipProgramGraphProvider", "com/minos/program/analysis/FileProgramGraphProvider",
            "com/minos/program/analysis/ProgramGraphEvaluator",
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
        ), "line": 0.65, "branch": 0.45,
    },
    "advanced-impact-security": {
        "prefixes": ("com/minos/program/analysis/AdvancedImpactService", "com/minos/program/analysis/SecurityAnalysisService"),
        "line": 0.47, "branch": 0.27,
    },
    "semantic-vector-store": {"prefixes": ("com/minos/storage/local/store/FileSemanticVectorStore",), "line": 0.45, "branch": 0.20},
    "semantic-learned-provider": {"prefixes": ("com/minos/application/semantic/OllamaEmbeddingProvider",), "line": 0.52, "branch": 0.32},
    "semantic-hybrid-retrieval": {
        "prefixes": (
            "com/minos/application/semantic/SemanticDocumentFactory", "com/minos/application/semantic/SemanticIndexService",
            "com/minos/application/semantic/SemanticSearchService", "com/minos/application/semantic/HybridSearchService",
            "com/minos/application/semantic/HybridContextBuilder",
        ), "line": 0.50, "branch": 0.30,
    },
    "advanced-public-api": {
        "prefixes": (
            "com/minos/api/AdvancedCodeIntelligenceApi", "com/minos/api/LocalAdvancedCodeIntelligenceApi",
            "com/minos/api/SemanticCodeIntelligenceApi", "com/minos/api/LocalSemanticCodeIntelligenceApi",
        ), "line": 0.65, "branch": 0.45,
    },
    "m19-m20-mcp-catalogue": {"prefixes": ("com/minos/mcp/MinosMcpTools",), "line": 0.50, "branch": 0.30},
    "m24-polyglot-provider-platform": {
        "prefixes": (
            "com/minos/orchestration/ProviderConformanceKit", "com/minos/orchestration/ProviderOperationalProfile",
            "com/minos/adapter/scip/ScipIndexerCatalog", "com/minos/adapter/scip/runtime/install/ManagedPolyglotScipRuntimeManager",
            "com/minos/adapter/scip/runtime/plan/BoundedProviderSourceProbe", "com/minos/adapter/scip/runtime/plan/ScipJavaProcessPlanFactory",
            "com/minos/adapter/scip/runtime/plan/ScipClangProcessPlanFactory", "com/minos/adapter/scip/runtime/plan/ScipDotnetProcessPlanFactory",
            "com/minos/adapter/scip/runtime/plan/ScipGoProcessPlanFactory", "com/minos/adapter/scip/runtime/plan/RustAnalyzerScipProcessPlanFactory",
            "com/minos/adapter/scip/runtime/install/StrongOwnedProcessExecutors",
            # AUD-TST-02: Linux 0.711 / 0.572, Windows 0.668 / 0.548 (pr-ci.yml runs 38719668720 (develop 83dd1fbb) and 38697957273 (develop ae7b02b0), 2026-10-15, aggregate report of each system); class floor = the lower measure
            # minus about 5 points.
            "com/minos/adapter/scip/runtime/install/ManagedScipProviderRuntimeManager",
        ),
        "line": 0.50,
        "branch": 0.28,
        "prefixMinimums": {
            "com/minos/adapter/scip/runtime/install/ManagedScipProviderRuntimeManager": {"line": 0.61, "branch": 0.49},
            "com/minos/adapter/scip/runtime/install/ManagedPolyglotScipRuntimeManager": {"line": 0.28, "branch": 0.20},
            "com/minos/adapter/scip/runtime/plan/BoundedProviderSourceProbe": {"line": 0.55, "branch": 0.35},
            "com/minos/adapter/scip/runtime/plan/ScipJavaProcessPlanFactory": {"line": 0.35, "branch": 0.15},
        },
    },
    "m25-remote-distributed-indexing": {
        "prefixes": (
            "com/minos/remote/", "com/minos/integration/git/JGitRemoteRepositoryMaterializer", "com/minos/integration/git/JGitCloneDeadline",
            "com/minos/integration/git/RemoteRepositoryCachePolicy", "com/minos/runtime/local/distributed/DistributedArtifactBundleStore",
            "com/minos/runtime/local/distributed/DistributedArtifactCachePolicy", "com/minos/runtime/local/distributed/DistributedIndexerExecutor",
            "com/minos/runtime/local/LocalIsolatedIndexWorker", "com/minos/runtime/local/sandbox/WorkerSandboxBackend",
            "com/minos/runtime/local/sandbox/WorkerSandboxQualification", "com/minos/cli/operations/LocalRemoteIndexOperations",
            # A2 / ADR 0042: the M25 wiring LocalRemoteIndexOperations used to build moved here verbatim.
            "com/minos/bootstrap/LocalRemoteIndexingRuntime",
            "com/minos/cli/command/RemoteIndexCommand",
        ),
        "line": 0.70,
        "branch": 0.50,
        "prefixMinimums": {
            "com/minos/integration/git/JGitCloneDeadline": {"line": 0.48, "branch": 0.42},
            # LocalRemoteIndexOperations owns the RemoteMaterialization release lifecycle (AUDIT-01):
            # a per-class floor stops that specific coverage from being able to hide behind the
            # rest of this scope's well-covered siblings while quietly regressing itself.
            "com/minos/cli/operations/LocalRemoteIndexOperations": {"line": 0.65, "branch": 0.35},
        },
    },
    "provider-execution-trust-boundary": {
        "prefixes": (
            "com/minos/runtime/local/process/ProcessIndexerExecutor", "com/minos/runtime/local/StrongProcessOwnershipIndexerExecutor",
            "com/minos/runtime/local/workspace/LocalProviderWorkspace", "com/minos/runtime/local/workspace/ProviderWorkspaceFiles",
            "com/minos/runtime/local/sandbox/WorkerSandboxBackend", "com/minos/runtime/local/sandbox/WorkerSandboxQualification",
            "com/minos/runtime/local/sandbox/WorkerSandboxBackends", "com/minos/runtime/local/sandbox/WorkerResourceContainment",
            "com/minos/runtime/local/process/CommandLocator", "com/minos/runtime/local/process/ProviderProcessEnvironment",
            "com/minos/runtime/local/process/ProcessTreeTermination", "com/minos/runtime/local/workspace/ProviderResidueReclamation",
        ),
        "line": 0.68,
        "branch": 0.48,
        "prefixMinimums": {
            "com/minos/runtime/local/StrongProcessOwnershipIndexerExecutor": {"line": 0.35, "branch": 0.18},
            "com/minos/runtime/local/process/ProviderProcessEnvironment": {"line": 0.70, "branch": 0.20},
        },
    },
    "provider-write-quota-supervisor": {
        "prefixes": ("com/minos/runtime/local/workspace/ProviderWriteQuotaSupervisor",),
        "line": 0.55,
        "branch": 0.35,
    },
    "provider-sandbox-linux": {
        "prefixes": (
            "com/minos/runtime/local/sandbox/LinuxBubblewrapWorkerSandboxBackend",
            "com/minos/runtime/local/sandbox/LinuxCgroupJob",
            # AUD-TST-02: the cgroup v2 ownership of a process tree is Linux code.
            "com/minos/runtime/local/sandbox/CgroupJobOwnership",
        ),
        "platform": "linux",
        "line": 0.55,
        "branch": 0.35,
        # Measured on Linux (pr-ci.yml runs 38719668720 (develop 83dd1fbb) and 38697957273 (develop ae7b02b0), 2026-10-15, aggregate report of each system): line 175/180 (0.972), branch 89/96 (0.927). Floor = measure minus about
        # 5 points, so the class cannot hide behind its siblings.
        "prefixMinimums": {
            "com/minos/runtime/local/sandbox/CgroupJobOwnership": {"line": 0.92, "branch": 0.87},
        },
    },
    "provider-sandbox-windows": {
        "prefixes": (
            "com/minos/runtime/local/sandbox/WindowsAppContainerWorkerSandboxBackend",
            "com/minos/runtime/local/sandbox/WindowsContainmentScript",
            # AUD-TST-12: the Job Object ownership is Windows-only code (its test is @EnabledOnOs(WINDOWS)).
            "com/minos/runtime/local/sandbox/WindowsJobObjectProcessOwnership",
        ),
        "platform": "windows",
        "line": 0.55,
        "branch": 0.35,
        # Measured on Windows (the class and its anonymous inner class), pr-ci.yml run 38470947827 (develop 90c24fd0),
        # confirmed by run 38466461171, 2026-10-14: line 67/68 (0.985), branch 15/20 (0.750). Floor = measure minus
        # about 5 points, so the class cannot hide behind its two siblings.
        "prefixMinimums": {
            "com/minos/runtime/local/sandbox/WindowsJobObjectProcessOwnership": {"line": 0.93, "branch": 0.70},
        },
    },
    "m26-runtime-dynamic-intelligence": {
        "prefixes": ("com/minos/dynamic/", "com/minos/application/dynamic/", "com/minos/storage/local/store/FileRuntimeObservationStore", "com/minos/cli/command/RuntimeCommand", "com/minos/output/RuntimeIntelligenceRenderer"),
        "line": 0.55, "branch": 0.35,
    },
    "m27-team-hosted-control-plane": {
        "prefixes": (
            "com/minos/hosted/", "com/minos/storage/local/store/FileHostedControlPlaneStore", "com/minos/storage/local/store/EnvironmentHostedTenantKeyProvider",
            "com/minos/cli/command/TeamCommand", "com/minos/api/LocalMinosTeamApi", "com/minos/output/HostedControlPlaneRenderer",
        ), "line": 0.45, "branch": 0.25,
    },
    "m29-backend-routing": {
        "prefixes": (
            "com/minos/app/McpBackend", "com/minos/app/McpBackendConfiguration", "com/minos/app/McpBackendConfigurationStore",
            "com/minos/app/McpBackendRouter",
        ),
        # minos-app's own tests are not carried by the aggregate report (0 of 10 lines there), so the scope reads
        # the report of the module that owns the classes, wherever its POM writes it.
        "report_of": "minos-app",
        "line": 0.55, "branch": 0.30,
    },
    # A3 / ADR 0044: DockerRuntimeBootstrap moved from minos-app to minos-cli (same FQN) with its tests. minos-app's
    # own report no longer contains it and the aggregate report does not carry minos-app's own test execution, so
    # it keeps the m29 floors in a scope of its own on the aggregate report (where minos-cli's tests are counted).
    "m29-docker-runtime-bootstrap": {
        "prefixes": ("com/minos/cli/DockerRuntimeBootstrap",),
        "line": 0.55, "branch": 0.30,
    },
    "m30-storage-backend-selection": {
        "prefixes": (
            "com/minos/storage/StorageBackend", "com/minos/storage/StorageBackendConfiguration", "com/minos/storage/StorageBackendProvider",
            "com/minos/application/StorageBackends", "com/minos/storage/local/LocalStorageBackend", "com/minos/storage/MinosRuntimeSettings",
            # A2 / ADR 0042: the selection logic moved verbatim from StorageBackends to the composition root.
            "com/minos/bootstrap/StorageBackendSelection",
        ), "line": 0.52, "branch": 0.32,
    },
    "m30-postgresql-pgvector": {
        "prefixes": (
            "com/minos/storage/postgresql/",
            # AUD-TST-04: the class prefix is declared beside the package prefix (a class matching both is counted
            # once) so that the runtime observation store cannot hide behind its 41 siblings.
            "com/minos/storage/postgresql/snapshot/PostgresRuntimeObservationStore",
        ),
        "line": 0.60,
        "branch": 0.40,
        # Measured on Linux (the only system that runs PostgreSQL; the scope is skipped on Windows), pr-ci.yml run
        # 38470947827 (develop 90c24fd0), confirmed by run 38466461171, 2026-10-14: line 65/65 (1.000),
        # branch 18/20 (0.900). Floor = measure minus about 5 points.
        "prefixMinimums": {
            "com/minos/storage/postgresql/snapshot/PostgresRuntimeObservationStore": {"line": 0.95, "branch": 0.85},
        },
    },
    "nexus-export": {"prefixes": ("com/minos/nexus/", "com/minos/integration/nexus/"), "line": 0.30, "branch": 0.12},
    # The lifecycle orchestrator behind every indexing run (register -> execute providers -> stage
    # -> promote -> persist, with commit-uncertain recovery and failure rollback). It is exercised
    # almost entirely indirectly through IndexingLifecycleService's test suite rather than directly,
    # so nothing previously stopped that indirect coverage from silently eroding.
    "critical-orchestration": {
        "prefixes": ("com/minos/orchestration/IndexingRunExecutor",),
        "line": 0.75,
        "branch": 0.55,
    },
    # R1 (ADR 0039): resuming an interrupted indexing run. The planner decides which artifacts are
    # reused (fail-closed on every discrepancy), the reconciler turns abandoned runs into
    # INTERRUPTED and the executor re-verifies reused artifacts before staging. A silent coverage
    # erosion here would let a stale artifact be published as a complete index.
    "resume-orchestration": {
        "prefixes": (
            "com/minos/orchestration/IndexingResumePlanner",
            "com/minos/orchestration/AuthoritativeProjectStateReconciler",
            "com/minos/orchestration/IndexingRunExecutor",
        ),
        "line": 0.75,
        "branch": 0.55,
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


def load_classes(report: Path, cache: dict[Path, list[ET.Element]]) -> list[ET.Element]:
    if report not in cache:
        if not report.is_file():
            raise FileNotFoundError(report)
        cache[report] = ET.parse(report).getroot().findall(".//class")
    return cache[report]


def floor_for(value: float | dict[str, float], platform: str) -> float | None:
    """A floor written once for every system, or per system ({"linux": .., "windows": ..}); None when absent here."""
    if isinstance(value, dict):
        return float(value[platform]) if platform in value else None
    return float(value)


def current_platform() -> str:
    if sys.platform.startswith("win"):
        return "windows"
    if sys.platform.startswith("linux"):
        return "linux"
    return "other"


ROOT = Path(__file__).resolve().parents[2]
ROOT_TARGET = "${maven.multiModuleProjectDirectory}/target"
MODULE_REPORT = Path("site/jacoco/jacoco.xml")
POM_NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}


def module_report(module: str, root: Path = ROOT) -> Path:
    """Where `module` writes its own JaCoCo report, read from its POM instead of assumed (AUD-TST-08).

    minos-app redirects its build directory to the root target/ (the shaded jar and the distribution scripts
    expect it there), so its report is the root one; a module without <directory> writes under its own target/.
    Any other value is refused: reading some other report would measure something else.
    """
    pom = root / module / "pom.xml"
    if not pom.is_file():
        raise ValueError(f"{module}: pom.xml not found, cannot tell where its JaCoCo report is written")
    directory = ET.parse(pom).getroot().findtext("m:build/m:directory", default="", namespaces=POM_NAMESPACE).strip()
    if not directory:
        return Path(module) / "target" / MODULE_REPORT
    if directory == ROOT_TARGET:
        return Path("target") / MODULE_REPORT
    raise ValueError(f"{module}: unrecognised build directory {directory!r}, cannot tell where its JaCoCo report is written")


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

    # 9-11. a scope that names a module reads the report where that module's POM writes it (AUD-TST-08).
    pom_template = ('<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
                    '<artifactId>{name}</artifactId>{build}</project>')

    def resolve(build: str) -> Path | str:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "minos-x").mkdir()
            (root / "minos-x" / "pom.xml").write_text(pom_template.format(name="minos-x", build=build), encoding="utf-8")
            try:
                return module_report("minos-x", root)
            except ValueError as error:
                return str(error)

    redirected = ("<build><directory>${maven.multiModuleProjectDirectory}/target</directory></build>")
    expect("module redirected to the root target", resolve(redirected), Path("target/site/jacoco/jacoco.xml"))
    expect("module with its own target", resolve("<build><plugins/></build>"),
           Path("minos-x/target/site/jacoco/jacoco.xml"))
    unknown = resolve("<build><directory>/somewhere/else</directory></build>")
    expect("unrecognised build directory is refused", isinstance(unknown, str) and "minos-x" in unknown
           and "/somewhere/else" in unknown, True)

    # 12-16. floors per platform and named exclusions (AUD-TST-01).
    here = current_platform()
    elsewhere = "windows" if here != "windows" else "linux"
    package = ("com/minos/selftest/",)
    code, failed = run({"per-platform": {"prefixes": package, "line": {here: 0.60, elsewhere: 0.99},
                                         "branch": {here: 0.55, elsewhere: 0.99}}})
    expect("floor of the current platform met: exit", code, 0)
    expect("floor of the current platform met: failures", failed, [])
    code, failed = run({"per-platform": {"prefixes": package, "line": {here: 0.70, elsewhere: 0.10},
                                         "branch": {here: 0.10, elsewhere: 0.10}}})
    expect("measure under the floor of the current platform: exit", code, 1)
    expect("measure under the floor of the current platform: failures", failed, ["per-platform"])
    code, failed = run({"per-platform": {"prefixes": package, "line": {elsewhere: 0.10}, "branch": {elsewhere: 0.10}}})
    expect("no floor for the current platform fails, never SKIPPED: exit", code, 1)
    expect("no floor for the current platform fails, never SKIPPED: failures", failed, ["per-platform"])
    code, failed = run({"excluding": {"prefixes": package, "exclusions": ("com/minos/selftest/Weak",),
                                      "line": 0.85, "branch": 0.75}})
    expect("named exclusion applied: exit", code, 0)
    expect("named exclusion applied: failures", failed, [])
    code, failed = run({"excluding": {"prefixes": package, "exclusions": ("com/minos/selftest/Gone",),
                                      "line": 0.10, "branch": 0.10}})
    expect("exclusion that matches no class: exit", code, 1)
    expect("exclusion that matches no class: failures", failed, ["excluding"])

    if failures:
        print("MINOS JACOCO GATE SELF-TEST FAILED", file=sys.stderr)
        for failure in failures:
            print(f" - {failure}", file=sys.stderr)
        return 1
    print("MINOS JACOCO GATE SELF-TEST SUCCESS (16 scenarios)")
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

        if "report_of" in config:
            try:
                report = module_report(config["report_of"])
            except ValueError as error:
                failures.append(f"{name}: {error}")
                results["scopes"][name] = {"classes": 0, "status": "FAIL"}
                continue
        else:
            report = Path(str(config.get("report", default_report)))
        try:
            all_classes = load_classes(report, cache)
        except FileNotFoundError:
            failures.append(f"{name}: JaCoCo report not found: {report}")
            results["scopes"][name] = {"classes": 0, "report": str(report), "status": "FAIL"}
            continue

        prefixes = tuple(config["prefixes"])
        # AUD-TST-01: classes left out by name (e.g. com/minos/app/, whose own tests the aggregate report does not
        # carry). Each exclusion must still designate a class of the scope, like a prefix.
        exclusions = tuple(config.get("exclusions", ()))
        dead_exclusions = sorted(
            exclusion for exclusion in exclusions
            if not any(clazz.attrib.get("name", "").startswith(exclusion) for clazz in all_classes
                       if clazz.attrib.get("name", "").startswith(prefixes)))
        if dead_exclusions:
            failures.append(f"{name}: exclusion(s) match no class of the scope in {report}: {', '.join(dead_exclusions)}")
            results["scopes"][name] = {"classes": 0, "report": str(report), "status": "FAIL",
                                       "deadExclusions": dead_exclusions}
            continue
        classes = [clazz for clazz in all_classes if clazz.attrib.get("name", "").startswith(prefixes)
                   and not clazz.attrib.get("name", "").startswith(exclusions)]
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
        line_min = floor_for(config["line"], platform)
        branch_min = floor_for(config["branch"], platform)
        if line_min is None or branch_min is None:
            # A scope that floors each system separately and has no floor here fails: it is not skipped.
            failures.append(f"{name}: no line/branch floor for platform {platform} (line={line:.3f}, "
                            f"branch={branch:.3f})")
            results["scopes"][name] = {"classes": len(classes), "report": str(report), "line": round(line, 6),
                                       "branch": round(branch, 6), "status": "FAIL",
                                       "reason": f"no floor for platform {platform}"}
            continue
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
        if exclusions:
            result["exclusions"] = list(exclusions)
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