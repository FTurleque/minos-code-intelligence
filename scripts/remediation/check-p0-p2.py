#!/usr/bin/env python3
"""Static consistency gate for P0-P2 audit remediation."""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        raise RuntimeError(f"missing required file: {relative}")
    return path.read_text(encoding="utf-8")


def require(relative: str, text: str, value: str) -> None:
    if value not in text:
        raise RuntimeError(f"{relative}: missing required evidence: {value}")


def forbid(relative: str, text: str, value: str) -> None:
    if value in text:
        raise RuntimeError(f"{relative}: forbidden stale construction: {value}")


def section(text: str, heading: str, next_heading: str | None = None) -> str:
    pattern = rf"(?ms)^### {re.escape(heading)}\s*$\n(.*?)"
    if next_heading is not None:
        pattern += rf"(?=^### {re.escape(next_heading)}\s*$)"
    else:
        pattern += r"(?=^## |\Z)"
    match = re.search(pattern, text)
    if not match:
        raise RuntimeError(f"cannot locate documentation section: {heading}")
    return match.group(1)


def forbid_production_sonar_suppressions() -> None:
    suppression = re.compile(r'@SuppressWarnings\(\s*(?:"java:S|\{[^}]*"java:S)', re.DOTALL)
    for module in ROOT.iterdir():
        source_root = module / "src" / "main" / "java"
        if not source_root.is_dir():
            continue
        for source in source_root.rglob("*.java"):
            text = source.read_text(encoding="utf-8")
            if suppression.search(text):
                raise RuntimeError(f"{source.relative_to(ROOT)}: production Sonar suppression is forbidden")
            if "breaks static taint-analysis tracking" in text or "break static taint-analysis tracking" in text:
                raise RuntimeError(f"{source.relative_to(ROOT)}: taint-analysis disruption is forbidden")


def forbid_postgres_connection_escape() -> None:
    source_root = ROOT / "minos-storage-postgresql" / "src" / "main" / "java"
    for source in source_root.rglob("*.java"):
        text = source.read_text(encoding="utf-8")
        if "connections.open()" in text:
            raise RuntimeError(f"{source.relative_to(ROOT)}: raw PostgreSQL connection ownership is forbidden")
    factory = read(
        "minos-storage-postgresql/src/main/java/com/minos/storage/postgresql/PostgresConnectionFactory.java"
    )
    if re.search(r"\bConnection\s+open\s*\(", factory):
        raise RuntimeError("PostgresConnectionFactory.java: Connection must not escape the factory lifecycle")


def main() -> int:
    try:
        application = read("minos-application/src/main/java/com/minos/application/MinosApplication.java")
        graph_service = read("minos-application/src/main/java/com/minos/program/analysis/ProgramGraphService.java")
        fingerprint_provider = read(
            "minos-application/src/main/java/com/minos/program/analysis/"
            "FingerprintConstrainedJavaProgramGraphProvider.java"
        )
        application_test = read(
            "minos-application/src/test/java/com/minos/application/MinosApplicationTest.java"
        )
        api_test = read(
            "minos-api/src/test/java/com/minos/api/AdvancedCodeIntelligenceApiContractTest.java"
        )
        product_facts = read("scripts/docs/product-facts.py")
        generated_facts = read("docs/generated/product-facts.md")
        architecture = read("scripts/architecture/check-module-boundaries.py")
        public_surfaces = read("docs/developer/public-surfaces.md")
        worker = read("minos-runtime-local/src/main/java/com/minos/runtime/LocalIsolatedIndexWorker.java")
        sandbox = read("minos-runtime-local/src/main/java/com/minos/runtime/WorkerSandboxBackend.java")
        worker_test = read(
            "minos-runtime-local/src/test/java/com/minos/runtime/LocalIsolatedIndexWorkerTest.java"
        )
        hosted = read("minos-application/src/main/java/com/minos/hosted/HostedControlPlaneService.java")
        hosted_auth = read("minos-application/src/main/java/com/minos/hosted/HostedAuthorizationService.java")
        hosted_audit = read("minos-application/src/main/java/com/minos/hosted/HostedAuditChain.java")
        hosted_test = read(
            "minos-application/src/test/java/com/minos/hosted/HostedControlPlaneServiceTest.java"
        )
        c0_research = read("docs/research/code-intelligence-architecture-analysis.md")

        docker_transport = read("minos-app/src/main/java/com/minos/cli/DockerMcpTransport.java")
        pg_connections = read(
            "minos-storage-postgresql/src/main/java/com/minos/storage/postgresql/PostgresConnectionFactory.java"
        )
        pg_migrator = read(
            "minos-storage-postgresql/src/main/java/com/minos/storage/postgresql/PostgresSchemaMigrator.java"
        )
        pg_registry = read(
            "minos-storage-postgresql/src/main/java/com/minos/storage/postgresql/PostgresProjectRegistry.java"
        )
        location = read("minos-domain/src/main/java/com/minos/domain/SymbolLocation.java")
        intellij_location = read(
            "minos-intellij/src/main/java/com/minos/intellij/navigation/MinosLocation.java"
        )
        file_snapshots = read(
            "minos-storage-local/src/main/java/com/minos/store/FileSymbolSnapshotStore.java"
        )
        mcp_tools = read("minos-mcp/src/main/java/com/minos/mcp/MinosMcpTools.java")
        mcp_server = read("minos-mcp/src/main/java/com/minos/mcp/MinosMcpServer.java")

        require("MinosApplication.java", application,
                "ProgramGraphService.productionProviders(effectiveFingerprints)")
        require("ProgramGraphService.java", graph_service,
                "new FingerprintConstrainedJavaProgramGraphProvider(fingerprints)")
        require("ProgramGraphService.java", graph_service, "public CacheStats cacheStats()")
        require("FingerprintConstrainedJavaProgramGraphProvider.java", fingerprint_provider,
                "SOURCE_DIFFERS_FROM_SNAPSHOT_FINGERPRINT")
        require("MinosApplicationTest.java", application_test,
                "productionCompositionExposesM22CapabilitiesFromOpen")
        require("MinosApplicationTest.java", application_test, "MinosApplication.open")
        require("MinosApplicationTest.java", application_test,
                "ProgramGraphCapability.CONTROL_FLOW")
        require("AdvancedCodeIntelligenceApiContractTest.java", api_test,
                "new LocalAdvancedCodeIntelligenceApi(application)")
        require("AdvancedCodeIntelligenceApiContractTest.java", api_test,
                'graph.capabilities().contains("CONTROL_FLOW")')
        require("AdvancedCodeIntelligenceApiContractTest.java", api_test,
                'graph.capabilities().contains("LOCAL_DATA_FLOW")')
        require("AdvancedCodeIntelligenceApiContractTest.java", api_test,
                '"minos-java-source-v1".equals(edge.providerId())')
        forbid("AdvancedCodeIntelligenceApiContractTest.java", api_test,
               "edge.origin().providerId()")

        require("product-facts.py", product_facts, "qualified_descriptor_methods")
        require("product-facts.py", product_facts,
                'qualified M24 catalog must expose 7 providers')
        forbid("product-facts.py", product_facts,
               '("scipJava", "scipTypeScript", "scipPython")')
        provider_headings = re.findall(r"^### `([^`]+)`", generated_facts, re.MULTILINE)
        if provider_headings != [
            "scip-java", "scip-typescript", "scip-python", "scip-clang",
            "scip-dotnet", "scip-go", "rust-analyzer-scip"
        ]:
            raise RuntimeError(f"generated facts expose unexpected provider catalog: {provider_headings}")

        require("check-module-boundaries.py", architecture, "ALLOWED_DEPENDENCIES")
        require("check-module-boundaries.py", architecture, "internal Maven dependency cycle")
        require("check-module-boundaries.py", architecture, "dependencyPolicy=explicit-v1")

        m26 = section(public_surfaces, "M26 — runtime et dynamique",
                      "M27 — team / hosted control plane")
        m27 = section(public_surfaces, "M27 — team / hosted control plane")
        require("public-surfaces.md M26", m26, "minos_runtime_sessions")
        forbid("public-surfaces.md M26", m26, "minos_team_")
        require("public-surfaces.md M27", m27, "minos_team_tenant")
        require("public-surfaces.md M27", m27, "MINOS_TEAM_TOKEN")

        require("WorkerSandboxBackend.java", sandbox, "NetworkGuarantee.OS_ENFORCED")
        require("WorkerSandboxBackend.java", sandbox,
                "native worker cannot prove OS-level network denial")
        require("LocalIsolatedIndexWorker.java", worker, "WorkerSandboxBackend sandboxBackend")
        forbid("LocalIsolatedIndexWorker.java", worker, "boolean networkDenyEnforced")
        require("LocalIsolatedIndexWorkerTest.java", worker_test,
                "denyIsAcceptedOnlyThroughAnOsEnforcedBackend")

        if len(hosted.splitlines()) >= 500:
            raise RuntimeError("HostedControlPlaneService remains an oversized >=500-line hotspot")
        require("HostedAuthorizationService.java", hosted_auth, "authorizeMutation")
        require("HostedAuditChain.java", hosted_audit, "hosted audit event authentication failed")
        require("HostedControlPlaneServiceTest.java", hosted_test,
                "rejectsPersistedAuditEventWithInvalidHmac")

        for disposition in ("ADOPTER", "ADAPTER", "REJETER", "DIFFÉRER"):
            require("code-intelligence-architecture-analysis.md", c0_research, disposition)
        require("code-intelligence-architecture-analysis.md", c0_research, "issue #2")

        # Current complete-audit P2 regression barriers.
        forbid_production_sonar_suppressions()
        forbid_postgres_connection_escape()
        require("DockerMcpTransport.java", docker_transport, "CommandLocator.find(\"docker\")")
        require("DockerMcpTransport.java", docker_transport, "CommandLocator.invocation")
        require("DockerMcpTransport.java", docker_transport, "MAX_PROBE_OUTPUT_BYTES")
        require("PostgresConnectionFactory.java", pg_connections,
                'properties.setProperty("currentSchema", schema + ",public")')
        require("PostgresConnectionFactory.java", pg_connections, "withConnection(ConnectionWork<T> work)")
        forbid("PostgresConnectionFactory.java", pg_connections, "set_config('search_path'")
        require("PostgresSchemaMigrator.java", pg_migrator, "enquoteIdentifier")
        require("PostgresProjectRegistry.java", pg_registry,
                "LEFT JOIN projects p ON p.workspace_id=w.id")
        forbid("PostgresProjectRegistry.java", pg_registry, "projectIds(UUID workspaceId)")
        require("SymbolLocation.java", location, "startLine == endLine && endColumn < startColumn")
        require("MinosLocation.java", intellij_location, "startColumn must not be negative")
        require("ProgramGraphService.java", graph_service, "ReentrantLock[] buildLocks")
        require("ProgramGraphService.java", graph_service, "buildLock.lock()")
        require("ProgramGraphService.java", graph_service, "buildLock.unlock()")
        forbid("ProgramGraphService.java", graph_service, "synchronized (buildLock(")
        require("FileSymbolSnapshotStore.java", file_snapshots, "ReentrantLock[] buildLocks")
        require("FileSymbolSnapshotStore.java", file_snapshots, "buildLock.lock()")
        require("FileSymbolSnapshotStore.java", file_snapshots, "buildLock.unlock()")
        forbid("FileSymbolSnapshotStore.java", file_snapshots, "synchronized (buildLock(")
        require("MinosMcpTools.java", mcp_tools, "error: MINOS tool execution failed")
        forbid("MinosMcpTools.java", mcp_tools, "effective.getMessage()")
        forbid("MinosMcpServer.java", mcp_server,
               '"error: MINOS MCP bootstrap failed: "')

        print("P0-P2 AUDIT REMEDIATION CONSISTENCY SUCCESS")
        return 0
    except Exception as exception:
        print(f"P0-P2 AUDIT REMEDIATION CONSISTENCY FAILED: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
