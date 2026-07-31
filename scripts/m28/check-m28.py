#!/usr/bin/env python3
"""Static M28 convergence, vertical-surface, boundary and decomposition gate."""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ANALYSIS = ROOT / "minos-application/src/main/java/com/minos/program/analysis"
HOSTED = ROOT / "minos-application/src/main/java/com/minos/hosted"
ENGINE_HOSTED = ROOT / "minos-engine/src/main/java/com/minos/hosted"
RUNTIME = ROOT / "minos-runtime-local/src/main/java/com/minos/runtime"


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        raise RuntimeError(f"missing required file: {relative}")
    return path.read_text(encoding="utf-8")


def require(name: str, text: str, value: str) -> None:
    if value not in text:
        raise RuntimeError(f"{name}: missing required evidence: {value}")


def forbid(name: str, text: str, value: str) -> None:
    if value in text:
        raise RuntimeError(f"{name}: forbidden responsibility remains in facade: {value}")


def main() -> int:
    try:
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
            "minos-application/src/test/java/com/minos/application/"
            "ProgramGraphPerformanceQualificationTest.java"
        )
        application_test = read(
            "minos-application/src/test/java/com/minos/application/MinosApplicationTest.java"
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
            "minos-runtime-local/src/main/java/com/minos/runtime/WorkerSandboxBackend.java"
        )
        sandbox_qualification = read(
            "minos-runtime-local/src/main/java/com/minos/runtime/WorkerSandboxQualification.java"
        )
        sandbox_test = read(
            "minos-runtime-local/src/test/java/com/minos/runtime/WorkerSandboxQualificationTest.java"
        )
        worker = read(
            "minos-runtime-local/src/main/java/com/minos/runtime/LocalIsolatedIndexWorker.java"
        )
        remote_doc = read("docs/user/remote-indexing.md")

        hosted_facade = read(
            "minos-application/src/main/java/com/minos/hosted/HostedControlPlaneService.java"
        )
        hosted_boundary = read(
            "minos-application/src/main/java/com/minos/hosted/HostedProductionBoundary.java"
        )
        hosted_test = read(
            "minos-application/src/test/java/com/minos/hosted/HostedProductionBoundaryTest.java"
        )

        if len(facade.splitlines()) > 80:
            raise RuntimeError("JavaSourceProgramGraphProvider must remain a <=80-line facade")
        require("JavaSourceProgramGraphProvider.java", facade, "JavaProgramGraphEngine engine")
        for forbidden in ("TreeScanner", "JavacTask", "MessageDigest", "Files.readAllBytes"):
            forbid("JavaSourceProgramGraphProvider.java", facade, forbidden)

        for filename, evidence in components.items():
            text = (ANALYSIS / filename).read_text(encoding="utf-8")
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

        if len(hosted_facade.splitlines()) > 260:
            raise RuntimeError("HostedControlPlaneService must remain a <=260-line facade")
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
            if not (HOSTED / port).is_file():
                raise RuntimeError(f"missing hosted production port: {port}")
        if not (ENGINE_HOSTED / "HostedTenantKeyProvider.java").is_file():
            raise RuntimeError("missing hosted production port: minos-engine/HostedTenantKeyProvider.java")
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

        if not (RUNTIME / "WorkerSandboxQualification.java").is_file():
            raise RuntimeError("missing worker sandbox qualification model")

        print("M28 CONVERGENCE, VERTICAL SURFACE, BOUNDARY AND DECOMPOSITION CONSISTENCY SUCCESS")
        return 0
    except Exception as exception:
        print(
            f"M28 CONVERGENCE, VERTICAL SURFACE, BOUNDARY AND DECOMPOSITION CONSISTENCY FAILED: {exception}",
            file=sys.stderr,
        )
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
