#!/usr/bin/env python3
"""Static consistency gate for M22 Advanced Provider Intelligence."""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        raise RuntimeError(f"missing required file: {relative}")
    return path.read_text(encoding="utf-8")


def require(relative: str, text: str, expected: str) -> None:
    if expected not in text:
        raise RuntimeError(f"{relative}: missing expected text: {expected}")


def forbid(relative: str, text: str, forbidden: str) -> None:
    if forbidden in text:
        raise RuntimeError(f"{relative}: forbidden text present: {forbidden}")


def main() -> int:
    try:
        provider_path = "minos-application/src/main/java/com/minos/program/analysis/JavaSourceProgramGraphProvider.java"
        service_path = "minos-application/src/main/java/com/minos/program/analysis/ProgramGraphService.java"
        test_path = "minos-application/src/test/java/com/minos/program/analysis/JavaSourceProgramGraphProviderTest.java"
        roadmap_path = "docs/roadmap/M22_EXECUTION.md"
        provider_doc_path = "docs/developer/java-advanced-provider.md"
        adr_path = "docs/adr/0030-java-ast-reference-provider-with-explicit-capability-limits.md"
        quality_path = "scripts/quality/check-jacoco.py"
        runner_path = "scripts/m22/run-final.ps1"

        provider = read(provider_path)
        service = read(service_path)
        tests = read(test_path)
        roadmap = read(roadmap_path)
        provider_doc = read(provider_doc_path)
        adr = read(adr_path)
        quality = read(quality_path)
        runner = read(runner_path)

        for expected in (
            'PROVIDER_ID = "minos-java-source-v1"',
            'SECURITY_CONFIG = ".minos/java-advanced-provider.properties"',
            "MAX_SOURCE_FILES = 2_000",
            "MAX_SOURCE_BYTES = 4L * 1024L * 1024L",
            "MAX_TOTAL_SOURCE_BYTES = 64L * 1024L * 1024L",
            'List.of("-proc:none", "-Xlint:none")',
            "new Origin(PROVIDER_ID, \"JAVA_COMPILER_AST\"",
            "OriginType.AST",
            "OriginType.DERIVED_BY_MINOS",
            "ProgramGraphCapability.CONTROL_FLOW",
            "ProgramGraphCapability.LOCAL_DATA_FLOW",
            "ProgramGraphCapability.INTERPROCEDURAL_DATA_FLOW",
            "ProgramGraphCapability.SECURITY_TAINT",
            "JAVA_AST_PARSE_ONLY_TYPE_ATTRIBUTION_NOT_PROVEN",
            "JAVA_LOCAL_DATA_FLOW_NAME_BASED_WITHIN_METHOD",
            "JAVA_INTERPROCEDURAL_UNIQUE_NAME_ARITY_ONLY",
            "JAVA_SECURITY_FLOW_INTRAPROCEDURAL_CONFIGURED_RULES_ONLY",
            "JAVA_ADVANCED_PROVIDER_PARSE_FAILED",
            "TreeScanner",
            "JavacTask",
        ):
            require(provider_path, provider, expected)

        # M22 v1 intentionally refuses guessed/full compiler attribution. A future
        # provider may add it only with explicit classpath provenance and new proof.
        forbid(provider_path, provider, "task.analyze(")
        forbid(provider_path, provider, "Trees.instance(task).getElement")

        require(service_path, service, "new JavaSourceProgramGraphProvider()")
        require(service_path, service, "new RelationshipProgramGraphProvider()")
        require(service_path, service, "new FileProgramGraphProvider()")

        for kind in ("CONTROL_FLOW", "DEF_USE", "ARGUMENT_FLOW", "RETURN_FLOW", "TAINT_FLOW"):
            require(test_path, tests, f"ProgramEdgeKind.{kind}")
        require(test_path, tests, "ProgramGraphEvaluator().evaluate")
        require(test_path, tests, "evaluation.perfect()")
        require(test_path, tests, "securityIsNeverClaimedWithoutExplicitRulesAndCacheTracksSourceChanges")
        require(test_path, tests, "nonJavaSnapshotAndSyntaxFailureStayFailClosed")

        for fixture in (
            "CfgFixture.java",
            "DefUseFixture.java",
            "InterproceduralFixture.java",
            "SecurityFixture.java",
        ):
            fixture_path = ROOT / "fixtures/m22/java-advanced-provider/project/src/main/java/demo" / fixture
            if not fixture_path.is_file():
                raise RuntimeError(f"missing M22 ground-truth fixture: {fixture_path.relative_to(ROOT)}")

        config_path = ROOT / "fixtures/m22/java-advanced-provider/project/.minos/java-advanced-provider.properties"
        if not config_path.is_file():
            raise RuntimeError("missing M22 security taxonomy fixture")

        require(quality_path, quality, '"java-advanced-provider"')
        require(quality_path, quality, "JavaSourceProgramGraphProvider")

        for expected in (
            "M22 — Advanced Provider Intelligence",
            "M22-S1",
            "M22-S2",
            "M22-S3",
            "M22-S4",
            "M22-S5",
            "M22-S6",
            "M22-S7",
            "M22-S8",
            "M22-S9",
            "precision=1.0 recall=1.0",
            "M21-S2/CI reste en pause jusqu’en août 2026",
        ):
            require(roadmap_path, roadmap, expected)

        require(provider_doc_path, provider_doc, "minos-java-source-v1")
        require(provider_doc_path, provider_doc, "JAVA_AST_PARSE_ONLY_TYPE_ATTRIBUTION_NOT_PROVEN")
        require(provider_doc_path, provider_doc, "SECURITY_TAINT")
        require(adr_path, adr, "Status: **Accepted**")
        require(adr_path, adr, "guessed-classpath")

        require(runner_path, runner, "scripts\\m21\\run-local.ps1")
        require(runner_path, runner, "scripts\\m21\\run-s5.ps1")
        require(runner_path, runner, "scripts\\m21\\run-s6.ps1")
        require(runner_path, runner, "jdk.compiler")
        require(runner_path, runner, "Assert-PackagedRuntimeContainsModule")
        require(runner_path, runner, "Expand-Archive")
        require(runner_path, runner, "lib\\modules")
        require(runner_path, runner, "MODULES=")
        require(runner_path, runner, "Qualified Windows ZIP")
        require(runner_path, runner, "M22 PACKAGED JDK.COMPILER RUNTIME SUCCESS")
        require(runner_path, runner, "M22 FINAL ADVANCED PROVIDER INTELLIGENCE VALIDATION SUCCESS")
        forbid(runner_path, runner, "app\\runtime\\bin\\java.exe")
        forbid(runner_path, runner, "workflow_dispatch")
        forbid(runner_path, runner, "rerun_workflow")
        forbid(runner_path, runner, ".github\\workflows")

        print("M22 ADVANCED PROVIDER CONSISTENCY SUCCESS")
        return 0
    except Exception as exception:
        print(f"M22 ADVANCED PROVIDER CONSISTENCY FAILED: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
