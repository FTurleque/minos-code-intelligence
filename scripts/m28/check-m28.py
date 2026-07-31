#!/usr/bin/env python3
"""Static M28 convergence, vertical-surface and decomposition gate."""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ANALYSIS = ROOT / "minos-application/src/main/java/com/minos/program/analysis"


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
        api_test = read(
            "minos-api/src/test/java/com/minos/api/AdvancedCodeIntelligenceApiContractTest.java"
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
        worker = read(
            "minos-runtime-local/src/main/java/com/minos/runtime/LocalIsolatedIndexWorker.java"
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

        vertical_tests = {
            "MinosApplicationTest.java": application_test,
            "AdvancedCodeIntelligenceApiContractTest.java": api_test,
            "M28VerticalProgramGraphCliTest.java": cli_test,
            "M28VerticalProgramGraphMcpTest.java": mcp_test,
        }
        for name, text in vertical_tests.items():
            require(name, text, "minos-java-source-v1")
            require(name, text, "CONTROL_FLOW")
            require(name, text, "LOCAL_DATA_FLOW")
        for name, text in {
            "M28VerticalProgramGraphCliTest.java": cli_test,
            "M28VerticalProgramGraphMcpTest.java": mcp_test,
        }.items():
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
        require("LocalIsolatedIndexWorker.java", worker, "DENY remains fail-closed")

        print("M28 CONVERGENCE, VERTICAL SURFACE AND DECOMPOSITION CONSISTENCY SUCCESS")
        return 0
    except Exception as exception:
        print(f"M28 CONVERGENCE, VERTICAL SURFACE AND DECOMPOSITION CONSISTENCY FAILED: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
