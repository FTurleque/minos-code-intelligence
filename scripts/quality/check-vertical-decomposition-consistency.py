#!/usr/bin/env python3
"""Static M28 convergence, vertical-surface, boundary and decomposition gate."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import literal_gate  # noqa: E402 - after the script directory is added to the path

ROOT = Path(__file__).resolve().parents[2]
ANALYSIS = "minos-application/src/main/java/com/minos/program/analysis"
HOSTED = "minos-engine/src/main/java/com/minos/hosted"
ENGINE_HOSTED = "minos-engine/src/main/java/com/minos/hosted"
RUNTIME = "minos-runtime-local/src/main/java/com/minos/runtime/local"
FACADE = f"{ANALYSIS}/JavaSourceProgramGraphProvider.java"
HOSTED_FACADE = f"{HOSTED}/HostedControlPlaneService.java"


def _padded_to(lines: int):
    """A mutation that grows a source file beyond ``lines`` lines."""
    return lambda text: text + "\n" * (lines + 1)


# Structured rules (facade sizes): one mutation each.
STRUCTURED_MUTATIONS = {
    "Java facade size": [(FACADE, _padded_to(80))],
    "hosted facade size": [(HOSTED_FACADE, _padded_to(260))],
}


def read(relative: str) -> str:
    return literal_gate.read(relative)


def require(name: str, text: str, value: str) -> None:
    literal_gate.require(name, text, value, f"{name}: missing required evidence: {value}")


def forbid(name: str, text: str, value: str) -> None:
    literal_gate.forbid(name, text, value, f"{name}: forbidden responsibility remains in facade: {value}")


def evaluate() -> str:
    """Assert the facts of the gate; return its success message."""
    facade = read(
        "minos-application/src/main/java/com/minos/program/analysis/"
        "JavaSourceProgramGraphProvider.java"
    )
    components = {
        "JavaSourceWorkspace.java": "static Discovery discover",
        "JavaAstParser.java": "ParseResult parse",
        "JavaDefUseAnalyzer.java": "final class JavaDefUseAnalyzer",
        "JavaControlFlowAnalyzer.java": "final class JavaControlFlowAnalyzer",
        "JavaInterproceduralFlowResolver.java": "final class JavaInterproceduralFlowResolver",
        "JavaTaintAnalyzer.java": "final class JavaTaintAnalyzer",
        "JavaProgramGraphAssembler.java": "final class JavaProgramGraphAssembler",
        "JavaProgramGraphEngine.java": "final class JavaProgramGraphEngine",
    }
    decomposition_test = read(
        "minos-application/src/test/java/com/minos/program/analysis/"
        "JavaSourceProgramGraphDecompositionTest.java"
    )
    performance_test = read(
        "minos-bootstrap/src/test/java/com/minos/bootstrap/application/"
        "ProgramGraphPerformanceQualificationTest.java"
    )
    application_test = read(
        "minos-bootstrap/src/test/java/com/minos/bootstrap/application/MinosApplicationTest.java"
    )
    api_contract_test = read(
        "minos-api/src/test/java/com/minos/api/AdvancedCodeIntelligenceApiContractTest.java"
    )
    api_vertical_test = read(
        "minos-api/src/test/java/com/minos/api/M28VerticalAdvancedApiTest.java"
    )
    cli_test = read(
        "minos-cli/src/test/java/com/minos/cli/M28VerticalProgramGraphCliTest.java"
    )
    mcp_test = read(
        "minos-mcp/src/test/java/com/minos/mcp/M28VerticalProgramGraphMcpTest.java"
    )
    windows_runner = read("scripts/m28/run-program-graph-performance.ps1")
    linux_runner = read("scripts/m28/run-program-graph-performance.sh")
    jacoco = read("scripts/quality/check-jacoco.py")

    sandbox = read(
        "minos-runtime-local/src/main/java/com/minos/runtime/local/sandbox/WorkerSandboxBackend.java"
    )
    sandbox_qualification = read(
        "minos-runtime-local/src/main/java/com/minos/runtime/local/sandbox/WorkerSandboxQualification.java"
    )
    sandbox_test = read(
        "minos-runtime-local/src/test/java/com/minos/runtime/local/sandbox/WorkerSandboxQualificationTest.java"
    )
    worker = read(
        "minos-runtime-local/src/main/java/com/minos/runtime/local/LocalIsolatedIndexWorker.java"
    )
    remote_doc = read("docs/user/remote-indexing.md")

    hosted_facade = read(
        "minos-engine/src/main/java/com/minos/hosted/HostedControlPlaneService.java"
    )
    hosted_boundary = read(
        "minos-engine/src/main/java/com/minos/hosted/HostedProductionBoundary.java"
    )
    hosted_test = read(
        "minos-engine/src/test/java/com/minos/hosted/HostedProductionBoundaryTest.java"
    )

    literal_gate.check("Java facade size", len(facade.splitlines()) <= 80,
                       "JavaSourceProgramGraphProvider must remain a <=80-line facade")
    require("JavaSourceProgramGraphProvider.java", facade, "JavaProgramGraphEngine engine")
    for forbidden in ("TreeScanner", "JavacTask", "MessageDigest", "Files.readAllBytes"):
        forbid("JavaSourceProgramGraphProvider.java", facade, forbidden)

    for filename, evidence in components.items():
        text = read(f"{ANALYSIS}/{filename}")
        require(filename, text, evidence)

    require("JavaSourceProgramGraphDecompositionTest.java", decomposition_test,
            "decomposedPipelineIsDeterministicAcrossTheControlledM22Corpus")
    require("JavaSourceProgramGraphDecompositionTest.java", decomposition_test,
            "publicProviderRemainsAThinFacadeAndResponsibilitiesStaySeparated")
    for capability in (
        "CONTROL_FLOW", "LOCAL_DATA_FLOW", "INTERPROCEDURAL_DATA_FLOW", "SECURITY_TAINT"
    ):
        require("JavaSourceProgramGraphDecompositionTest.java", decomposition_test, capability)

    require("ProgramGraphPerformanceQualificationTest.java", performance_test,
            "recordsColdWarmCacheHitAndModifiedSourceDisposition")
    require("ProgramGraphPerformanceQualificationTest.java", performance_test,
            "KEEP_FINGERPRINT_CONSTRAINED_IN_MEMORY_CACHE")
    require("ProgramGraphPerformanceQualificationTest.java", performance_test,
            "SOURCE_MISMATCH_LIMITATION")
    require("run-program-graph-performance.ps1", windows_runner,
            "M28 PROGRAM GRAPH PERFORMANCE QUALIFICATION SUCCESS")
    require("run-program-graph-performance.sh", linux_runner,
            "M28 PROGRAM GRAPH PERFORMANCE QUALIFICATION SUCCESS")

    require("MinosApplicationTest.java", application_test,
            "JavaSourceProgramGraphProvider.PROVIDER_ID")
    require("AdvancedCodeIntelligenceApiContractTest.java", api_contract_test,
            "new LocalAdvancedCodeIntelligenceApi(application)")
    for name, text in {
        "MinosApplicationTest.java": application_test,
        "M28VerticalAdvancedApiTest.java": api_vertical_test,
        "M28VerticalProgramGraphCliTest.java": cli_test,
        "M28VerticalProgramGraphMcpTest.java": mcp_test,
    }.items():
        require(name, text, "CONTROL_FLOW")
        require(name, text, "LOCAL_DATA_FLOW")
    for name, text in {
        "M28VerticalAdvancedApiTest.java": api_vertical_test,
        "M28VerticalProgramGraphCliTest.java": cli_test,
        "M28VerticalProgramGraphMcpTest.java": mcp_test,
    }.items():
        require(name, text, "minos-java-source-v1")
        require(name, text, "INTERPROCEDURAL_DATA_FLOW")
        require(name, text, "SECURITY_TAINT")
        require(name, text, "TAINT_FLOW")
        require(name, text, "DERIVED")

    for component in (
        "JavaSourceWorkspace", "JavaAstParser", "JavaDefUseAnalyzer",
        "JavaControlFlowAnalyzer", "JavaInterproceduralFlowResolver",
        "JavaTaintAnalyzer", "JavaProgramGraphAssembler", "JavaProgramGraphEngine",
        "FingerprintConstrainedJavaProgramGraphProvider",
    ):
        require("check-jacoco.py", jacoco, component)

    require("WorkerSandboxBackend.java", sandbox, "NetworkGuarantee.NONE")
    require("WorkerSandboxBackend.java", sandbox, "NetworkGuarantee.OS_ENFORCED")
    require("WorkerSandboxBackend.java", sandbox,
            "native worker cannot prove OS-level network denial")
    require("WorkerSandboxQualification.java", sandbox_qualification,
            "FAIL_CLOSED_NOT_ENFORCED")
    require("WorkerSandboxQualification.java", sandbox_qualification,
            "UNTRUSTED_CODE_UNSUPPORTED")
    require("WorkerSandboxQualification.java", sandbox_qualification,
            "BLOCKED_NO_RESTRICTED_TOKEN_JOB_OBJECT_BACKEND")
    require("WorkerSandboxQualification.java", sandbox_qualification,
            "BLOCKED_NO_NAMESPACE_SECCOMP_BACKEND")
    require("WorkerSandboxQualification.java", sandbox_qualification,
            "WORKER_SANDBOX_CLAIM_PROHIBITED")
    require("WorkerSandboxQualificationTest.java", sandbox_test,
            "nativeBackendIsExplicitlyProcessOnlyAndProhibitsSandboxClaim")
    require("WorkerSandboxQualificationTest.java", sandbox_test,
            "qualificationCannotClaimDenyOrUntrustedCodeWithoutOsEvidence")
    require("LocalIsolatedIndexWorker.java", worker, "DENY remains fail-closed")
    require("remote-indexing.md", remote_doc, "Il refuse `deny`")
    require("remote-indexing.md", remote_doc, "ne prouvent pas un blocage réseau au niveau OS")

    literal_gate.check("hosted facade size", len(hosted_facade.splitlines()) <= 260,
                       "HostedControlPlaneService must remain a <=260-line facade")
    for service in (
        "HostedTenantService", "HostedMembershipService", "HostedWorkspaceService",
        "HostedRetentionService", "HostedTokenService", "HostedAuthorizationService",
        "HostedAuditChain", "HostedTenantMutationWriter",
    ):
        require("HostedControlPlaneService.java", hosted_facade, service)
    for port in (
        "HostedIdentityProvider.java", "HostedAuditSink.java",
        "HostedTransportSecurityPort.java", "HostedAvailabilityPort.java",
    ):
        literal_gate.require_file(port, f"{HOSTED}/{port}", f"missing hosted production port: {port}")
    literal_gate.require_file("minos-engine/HostedTenantKeyProvider.java", f"{ENGINE_HOSTED}/HostedTenantKeyProvider.java",
                              "missing hosted production port: minos-engine/HostedTenantKeyProvider.java")
    require("HostedProductionBoundary.java", hosted_boundary, "EMBEDDED_LOCAL_FIRST")
    require("HostedProductionBoundary.java", hosted_boundary, "HOSTED_NETWORK_TRANSPORT_NOT_PROVIDED")
    require("HostedProductionBoundary.java", hosted_boundary, "HOSTED_BACKUP_AVAILABILITY_NOT_PROVIDED")
    require("HostedProductionBoundary.java", hosted_boundary, "HOSTED_SAAS_OPERATION_NOT_CLAIMED")
    require("HostedProductionBoundary.java", hosted_boundary, "HOSTED_PROCESS_ISOLATION_NOT_QUALIFIED")
    require("HostedProductionBoundaryTest.java", hosted_test,
            "embeddedBoundaryDoesNotClaimTransportAvailabilitySaasOrProcessIsolation")
    require("HostedProductionBoundaryTest.java", hosted_test,
            "externalAuditSinkReceivesPersistedAllowedAndDeniedEvents")
    require("HostedProductionBoundaryTest.java", hosted_test,
            "facadeStaysThinAndCohesiveServicesAreRealSourceFiles")

    literal_gate.require_file("worker sandbox qualification model", f"{RUNTIME}/sandbox/WorkerSandboxQualification.java",
                              "missing worker sandbox qualification model")

    return "M28 CONVERGENCE, VERTICAL SURFACE, BOUNDARY AND DECOMPOSITION CONSISTENCY SUCCESS"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parser.add_argument("--self-test", action="store_true",
                        help="refuse one in-memory mutation per asserted fact, then exit")
    if parser.parse_args(argv).self_test:
        return literal_gate.self_test(ROOT, evaluate, STRUCTURED_MUTATIONS)
    return literal_gate.run(ROOT, evaluate, "M28 CONVERGENCE, VERTICAL SURFACE, BOUNDARY AND DECOMPOSITION CONSISTENCY FAILED")


if __name__ == "__main__":
    raise SystemExit(main())
