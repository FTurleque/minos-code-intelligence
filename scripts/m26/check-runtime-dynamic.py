#!/usr/bin/env python3
"""Fail-closed structural gate for M26 runtime and dynamic intelligence."""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = "e37cf39fcf4f7e417c618fa0b16590100c1e0b91"


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        raise RuntimeError(f"missing required file: {relative}")
    return path.read_text(encoding="utf-8")


def require_facts(relative: str, text: str, *facts: str) -> None:
    for fact in facts:
        if fact not in text:
            raise RuntimeError(f"{relative}: missing required fact: {fact}")


def require_pattern(relative: str, text: str, pattern: str, label: str) -> None:
    if not re.search(pattern, text, re.MULTILINE | re.IGNORECASE):
        raise RuntimeError(f"{relative}: missing contract: {label}")


def forbid(relative: str, text: str, *values: str) -> None:
    lowered = text.casefold()
    for value in values:
        if value.casefold() in lowered:
            raise RuntimeError(f"{relative}: forbidden weakening or CI action: {value}")


def assert_no_workflow_changes() -> None:
    completed = subprocess.run(
        ["git", "diff", "--quiet", BASE, "HEAD", "--", ".github/workflows"], cwd=ROOT, check=False)
    if completed.returncode != 0:
        raise RuntimeError("M26 forbids changes under .github/workflows")


def main() -> int:
    try:
        model = read("minos-domain/src/main/java/com/minos/dynamic/RuntimeObservationSession.java")
        reference = read("minos-domain/src/main/java/com/minos/dynamic/RuntimeSymbolReference.java")
        resolution = read("minos-domain/src/main/java/com/minos/dynamic/RuntimeSymbolResolution.java")
        correlation = read("minos-domain/src/main/java/com/minos/dynamic/CorrelatedRuntimeObservation.java")
        port = read("minos-engine/src/main/java/com/minos/dynamic/RuntimeObservationStore.java")
        codec = read("minos-application/src/main/java/com/minos/dynamic/RuntimeObservationEnvelopeCodec.java")
        service = read("minos-application/src/main/java/com/minos/dynamic/RuntimeIntelligenceService.java")
        store = read("minos-storage-local/src/main/java/com/minos/store/FileRuntimeObservationStore.java")
        command = read("minos-cli/src/main/java/com/minos/cli/RuntimeCommand.java")
        app = read("minos-application/src/main/java/com/minos/application/MinosApplication.java")
        mcp = read("minos-mcp/src/main/java/com/minos/mcp/MinosMcpTools.java")
        backend = read("minos-mcp/src/main/java/com/minos/mcp/MinosApplicationMcpBackend.java")
        e2e = read("scripts/m26/run-runtime-e2e.py")
        windows = read("scripts/m26/run-final.ps1")
        linux = read("scripts/m26/run-final.sh")

        require_facts("RuntimeObservationSession.java", model,
                      "minos-runtime-observation-v1", "MAX_OBSERVATIONS = 1_000_000",
                      "Duration.ofDays(366)", "RuntimeObservationCompleteness.PARTIAL",
                      "M26 accepts PARTIAL runtime observations only")
        require_facts("RuntimeSymbolReference.java", reference,
                      "fileId must be project-relative", "fileId must be a confined normalized path",
                      "line requires fileId")
        require_facts("RuntimeSymbolResolution.java", resolution,
                      "RESOLVED", "AMBIGUOUS", "UNRESOLVED", "MAX_CANDIDATE_SYMBOL_IDS = 1_000",
                      "candidate symbol identities are invalid or exceed their limit")
        require_facts("CorrelatedRuntimeObservation.java", correlation,
                      "source resolution must describe the observation source",
                      "target resolution must describe the observation target")
        require_facts("RuntimeObservationStore.java", port, "interface RuntimeObservationStore",
                      "SaveResult", "Optional<CorrelatedRuntimeSession>", "List<CorrelatedRuntimeSession>")

        require_facts("RuntimeObservationEnvelopeCodec.java", codec,
                      "MAX_INPUT_BYTES = 64L * 1024L * 1024L", "CodingErrorAction.REPORT",
                      "must not contain a BOM", "unknown runtime observation kind", "completeness must be PARTIAL",
                      "Files.isSymbolicLink", "sha256")
        require_facts("RuntimeIntelligenceService.java", service,
                      "runtime session projectId does not match", "runtime session snapshot does not match active snapshot",
                      "OBSERVED_PARTIAL", "absence of an observation never proves",
                      "observedSymbolRatio is a correlation ratio", "RuntimeResolutionStatus.AMBIGUOUS",
                      "non-active snapshot")
        forbid("RuntimeIntelligenceService.java", service, ".publish(", "ProviderCapability", "ProjectDiscoveryService")

        require_facts("FileRuntimeObservationStore.java", store,
                      "DEFAULT_MAX_SESSIONS_PER_PROJECT = 128", "DEFAULT_MAX_PROJECT_BYTES",
                      "DEFAULT_MAX_SESSION_BYTES", "FileLock", "ATOMIC_MOVE", "checksum mismatch",
                      "session is immutable", "must not be a symbolic link", "capacity reached")
        require_facts("RuntimeCommand.java", command,
                      "runtime import", "runtime sessions", "runtime report", "runtime symbol",
                      "absenceMeaning: NOT_OBSERVED_IN_SELECTED_PARTIAL_SESSIONS", "--limit")
        require_facts("MinosApplication.java", app,
                      "runtime-observations", "RuntimeObservationStore", "RuntimeIntelligenceService")
        tool_count_match = re.search(r"TOOL_COUNT\s*=\s*(\d+)", mcp)
        if not tool_count_match or int(tool_count_match.group(1)) < 26:
            raise RuntimeError("MinosMcpTools.java: M26 requires its 26-tool catalogue or an additive superset")
        require_facts("MinosMcpTools.java", mcp,
                      "minos_runtime_sessions", "minos_runtime_report", "minos_runtime_symbol",
                      "sessionId", "additionalProperties")
        forbid("MinosMcpTools.java", mcp, "minos_runtime_import")
        require_facts("MinosApplicationMcpBackend.java", backend,
                      "runtimeIntelligenceService().listSessions", "runtimeIntelligenceService().report",
                      "runtimeIntelligenceService().symbolReport")

        tests = {
            "RuntimeObservationModelTest.java": read("minos-domain/src/test/java/com/minos/dynamic/RuntimeObservationModelTest.java"),
            "FileRuntimeObservationStoreTest.java": read("minos-storage-local/src/test/java/com/minos/store/FileRuntimeObservationStoreTest.java"),
            "RuntimeIntelligenceServiceTest.java": read("minos-application/src/test/java/com/minos/dynamic/RuntimeIntelligenceServiceTest.java"),
            "RuntimeCommandTest.java": read("minos-cli/src/test/java/com/minos/cli/RuntimeCommandTest.java"),
            "MinosMcpToolsTest.java": read("minos-mcp/src/test/java/com/minos/mcp/MinosMcpToolsTest.java"),
        }
        require_facts("RuntimeObservationModelTest.java", tests["RuntimeObservationModelTest.java"],
                      "rejectsUnconfinedOrIncompleteObservations", "correlationMustDescribeTheExactObservedReference")
        require_facts("FileRuntimeObservationStoreTest.java", tests["FileRuntimeObservationStoreTest.java"],
                      "TreatsTheSameSourceAsIdempotent", "refusesSessionIdentityMutationAndCapacityOverflow",
                      "detectsPersistedByteTampering", "rejectsAProjectDirectorySymlink")
        require_facts("RuntimeIntelligenceServiceTest.java", tests["RuntimeIntelligenceServiceTest.java"],
                      "ReportsResolutionHotPathsAndSymbolFacts", "rejectsProjectAndSnapshotMisalignment",
                      "codecFailsClosedOnBomTraversalUnknownKindsAndNonPartialCompleteness")
        require_facts("RuntimeCommandTest.java", tests["RuntimeCommandTest.java"],
                      "AcrossAllCliActions", "ActionSpecificRequiredOptionsAndBounds")
        require_facts("MinosMcpToolsTest.java", tests["MinosMcpToolsTest.java"],
                      "minos_runtime_sessions", "minos_runtime_report", "minos_runtime_symbol")

        require_facts("scripts/m26/run-runtime-e2e.py", e2e,
                      "M26 RUNTIME DYNAMIC END-TO-END SUCCESS", "completeRejected",
                      "sessionMutationRejected", "activeSnapshotAligned", "OBSERVED_PARTIAL")
        require_facts("scripts/m26/run-final.ps1", windows,
                      "ExpectedHead", "check-runtime-dynamic.py", "run-runtime-e2e.py",
                      "M26 FINAL RUNTIME DYNAMIC INTELLIGENCE VALIDATION SUCCESS")
        require_facts("scripts/m26/run-final.sh", linux,
                      "EXPECTED_HEAD", "check-runtime-dynamic.py", "run-runtime-e2e.py",
                      "M26 LINUX RUNTIME DYNAMIC INTELLIGENCE VALIDATION SUCCESS")
        forbid("scripts/m26/run-final.ps1", windows, "gh workflow", "gh run", "workflow_dispatch")
        forbid("scripts/m26/run-final.sh", linux, "gh workflow", "gh run", "workflow_dispatch")

        execution = read("docs/roadmap/M26_EXECUTION.md")
        adr = read("docs/adr/0034-partial-runtime-observations-with-explicit-static-correlation.md")
        user = read("docs/user/runtime-intelligence.md")
        developer = read("docs/developer/runtime-dynamic-intelligence.md")
        for relative, document in (("docs/roadmap/M26_EXECUTION.md", execution),
                                   ("docs/adr/0034-partial-runtime-observations-with-explicit-static-correlation.md", adr),
                                   ("docs/user/runtime-intelligence.md", user),
                                   ("docs/developer/runtime-dynamic-intelligence.md", developer)):
            require_facts(relative, document, "minos-runtime-observation-v1", "PARTIAL",
                          "OBSERVED_PARTIAL", "snapshot", "borné", "absence")
        require_facts("docs/roadmap/M26_EXECUTION.md", execution,
                      "#87", "#88", BASE, "M26-S9",
                      "M21-S2 / GitHub Actions reste **strictement en pause jusqu’en août 2026**")
        require_facts("docs/adr/0034-partial-runtime-observations-with-explicit-static-correlation.md", adr,
                      "Partial runtime observations with explicit static correlation",
                      "NOT_OBSERVED_IN_SELECTED_PARTIAL_SESSIONS")

        quality = read("scripts/quality/check-jacoco.py")
        require_facts("scripts/quality/check-jacoco.py", quality, '"m26-runtime-dynamic-intelligence"',
                      "FileRuntimeObservationStore", "RuntimeCommand")
        assert_no_workflow_changes()
        print("M26 RUNTIME DYNAMIC CONSISTENCY SUCCESS")
        return 0
    except Exception as exception:
        print(f"M26 RUNTIME DYNAMIC CONSISTENCY FAILED: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
