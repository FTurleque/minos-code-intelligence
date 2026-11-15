#!/usr/bin/env python3
"""Fail-closed structural gate for M26 runtime and dynamic intelligence."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import literal_gate  # noqa: E402 - after the script directory is added to the path

ROOT = Path(__file__).resolve().parents[2]
BASE = "e37cf39fcf4f7e417c618fa0b16590100c1e0b91"
MCP_TOOLS = "minos-mcp/src/main/java/com/minos/mcp/MinosMcpTools.java"

# Structured rule (a counted catalogue): one mutation makes it false.
STRUCTURED_MUTATIONS = {
    "MCP catalogue size": [(MCP_TOOLS, lambda text: re.sub(r"TOOL_COUNT\s*=\s*\d+", "TOOL_COUNT = 25", text))],
}


def read(relative: str) -> str:
    return literal_gate.read(relative)


def require_facts(relative: str, text: str, *facts: str) -> None:
    for fact in facts:
        literal_gate.require(relative, text, fact, f"{relative}: missing required fact: {fact}")


def require_pattern(relative: str, text: str, pattern: str, label: str) -> None:
    literal_gate.require_pattern(relative, text, pattern, f"{relative}: missing contract: {label}",
                                 re.MULTILINE | re.IGNORECASE)


def forbid(relative: str, text: str, *values: str) -> None:
    for value in values:
        literal_gate.forbid(relative, text, value, f"{relative}: forbidden weakening or CI action: {value}",
                            str.casefold)


def evaluate() -> str:
    """Assert the facts of the gate; return its success message."""
    model = read("minos-engine/src/main/java/com/minos/dynamic/RuntimeObservationSession.java")
    reference = read("minos-engine/src/main/java/com/minos/dynamic/RuntimeSymbolReference.java")
    resolution = read("minos-engine/src/main/java/com/minos/dynamic/RuntimeSymbolResolution.java")
    correlation = read("minos-engine/src/main/java/com/minos/dynamic/CorrelatedRuntimeObservation.java")
    port = read("minos-engine/src/main/java/com/minos/dynamic/RuntimeObservationStore.java")
    codec = read("minos-application/src/main/java/com/minos/application/dynamic/RuntimeObservationEnvelopeCodec.java")
    service = read("minos-application/src/main/java/com/minos/application/dynamic/RuntimeIntelligenceService.java")
    store = read("minos-storage/src/main/java/com/minos/storage/local/store/FileRuntimeObservationStore.java")
    bounded_lease = read("minos-engine/src/main/java/com/minos/io/BoundedFileLease.java")
    command = read("minos-cli/src/main/java/com/minos/cli/command/RuntimeCommand.java")
    app = read("minos-application/src/main/java/com/minos/application/MinosApplication.java")
    app_assembler = read("minos-application/src/main/java/com/minos/application/MinosApplicationAssembler.java")
    local_storage = read("minos-storage/src/main/java/com/minos/storage/local/LocalStorageBackend.java")
    mcp = read(MCP_TOOLS)
    mcp_schemas = read("minos-mcp/src/main/java/com/minos/mcp/McpToolSchemas.java")
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
                  "ConfinedFileOpener.openRegularFileNoFollow", "sha256")
    require_facts("RuntimeIntelligenceService.java", service,
                  "runtime session projectId does not match", "runtime session snapshot does not match active snapshot",
                  "OBSERVED_PARTIAL", "absence of an observation never proves",
                  "observedSymbolRatio is a correlation ratio", "RuntimeResolutionStatus.AMBIGUOUS",
                  "non-active snapshot")
    forbid("RuntimeIntelligenceService.java", service, ".publish(", "ProviderCapability", "ProjectDiscoveryService")

    require_facts("FileRuntimeObservationStore.java", store,
                  "DEFAULT_MAX_SESSIONS_PER_PROJECT = 128", "DEFAULT_MAX_PROJECT_BYTES",
                  "DEFAULT_MAX_SESSION_BYTES", "BoundedFileLease", "LOCK_TIMEOUT", "DurableAtomicFile.publish",
                  "LinkOption.NOFOLLOW_LINKS", "checksum mismatch", "session is immutable",
                  "must not be a symbolic link", "capacity reached")
    require_facts("BoundedFileLease.java", bounded_lease,
                  "FileLock", "LeaseDeadline", "PrivateLocalStorage", "LinkOption.NOFOLLOW_LINKS",
                  "tryLock", "preparePrivateLockFile")
    require_facts("RuntimeCommand.java", command,
                  "runtime import", "runtime sessions", "runtime report", "runtime symbol",
                  "absenceMeaning: NOT_OBSERVED_IN_SELECTED_PARTIAL_SESSIONS", "--limit")
    require_facts("MinosApplication.java", app,
                  "RuntimeObservationStore", "RuntimeIntelligenceService", "runtimeObservationStore()")
    require_facts("MinosApplicationAssembler.java", app_assembler,
                  "selected.runtimeObservationStore()", "effectiveRuntimeObservations")
    require_facts("LocalStorageBackend.java", local_storage,
                  'namespace(root, "runtime-observations")', "FileRuntimeObservationStore",
                  "DurableAtomicFile.ensureDirectory")
    tool_count_match = re.search(r"TOOL_COUNT\s*=\s*(\d+)", mcp)
    literal_gate.check("MCP catalogue size", bool(tool_count_match) and int(tool_count_match.group(1)) >= 26,
                       "MinosMcpTools.java: M26 requires its 26-tool catalogue or an additive superset")
    require_facts("MinosMcpTools.java", mcp,
                  "minos_runtime_sessions", "minos_runtime_report", "minos_runtime_symbol",
                  "sessionId")
    require_facts("McpToolSchemas.java", mcp_schemas,
                  "runtimeSessionsSchema", "runtimeReportSchema", "runtimeSymbolSchema",
                  "additionalProperties")
    forbid("MinosMcpTools.java", mcp, "minos_runtime_import")
    require_facts("MinosApplicationMcpBackend.java", backend,
                  "runtimeIntelligenceService().listSessions", "runtimeIntelligenceService().report",
                  "runtimeIntelligenceService().symbolReport")

    tests = {
        "RuntimeObservationModelTest.java": read("minos-engine/src/test/java/com/minos/dynamic/RuntimeObservationModelTest.java"),
        "FileRuntimeObservationStoreTest.java": read("minos-storage/src/test/java/com/minos/storage/local/store/FileRuntimeObservationStoreTest.java"),
        "FileRuntimeObservationStoreSymlinkTest.java": read("minos-storage/src/test/java/com/minos/storage/local/store/FileRuntimeObservationStoreSymlinkTest.java"),
        "RuntimeIntelligenceServiceTest.java": read("minos-application/src/test/java/com/minos/application/dynamic/RuntimeIntelligenceServiceTest.java"),
        # A3 / ADR 0044: the file-adapter half of the split runtime service test (real local stores).
        "RuntimeIntelligenceFileAdaptersTest.java": read("minos-bootstrap/src/test/java/com/minos/bootstrap/RuntimeIntelligenceFileAdaptersTest.java"),
        "RuntimeCommandTest.java": read("minos-cli/src/test/java/com/minos/cli/command/RuntimeCommandTest.java"),
        "MinosMcpToolsTest.java": read("minos-mcp/src/test/java/com/minos/mcp/MinosMcpToolsTest.java"),
    }
    require_facts("RuntimeObservationModelTest.java", tests["RuntimeObservationModelTest.java"],
                  "rejectsUnconfinedOrIncompleteObservations", "correlationMustDescribeTheExactObservedReference")
    require_facts("FileRuntimeObservationStoreTest.java", tests["FileRuntimeObservationStoreTest.java"],
                  "TreatsTheSameSourceAsIdempotent", "refusesSessionIdentityMutationAndCapacityOverflow",
                  "detectsPersistedByteTampering", "rejectsAProjectDirectorySymlink")
    require_facts("FileRuntimeObservationStoreSymlinkTest.java", tests["FileRuntimeObservationStoreSymlinkTest.java"],
                  "rejectsSymlinkedStoreRoot", "rejectsSymlinkedProjectLockLeaf",
                  "rejectsSymlinkedSessionLeaf")
    require_facts("RuntimeIntelligenceServiceTest.java", tests["RuntimeIntelligenceServiceTest.java"],
                  "ReportsResolutionHotPathsAndSymbolFacts", "rejectsProjectAndSnapshotMisalignment",
                  "codecFailsClosedOnBomTraversalUnknownKindsAndNonPartialCompleteness")
    require_facts("RuntimeIntelligenceFileAdaptersTest.java", tests["RuntimeIntelligenceFileAdaptersTest.java"],
                  "importsStrictPartialEvidenceAndReportsResolutionHotPathsAndSymbolFacts",
                  "rejectsProjectAndSnapshotMisalignmentAndStaleSessionQueries")
    require_facts("RuntimeCommandTest.java", tests["RuntimeCommandTest.java"],
                  "AcrossAllCliActions", "ActionSpecificRequiredOptionsAndBounds")
    require_facts("MinosMcpToolsTest.java", tests["MinosMcpToolsTest.java"],
                  "minos_runtime_sessions", "minos_runtime_report", "minos_runtime_symbol")

    require_facts("scripts/m26/run-runtime-e2e.py", e2e,
                  "M26 RUNTIME DYNAMIC END-TO-END SUCCESS", "completeRejected",
                  "sessionMutationRejected", "activeSnapshotAligned", "OBSERVED_PARTIAL")
    require_facts("scripts/m26/run-final.ps1", windows,
                  "ExpectedHead", "check-runtime-dynamic-consistency.py", "run-runtime-e2e.py",
                  "M26 FINAL RUNTIME DYNAMIC INTELLIGENCE VALIDATION SUCCESS")
    require_facts("scripts/m26/run-final.sh", linux,
                  "EXPECTED_HEAD", "check-runtime-dynamic-consistency.py", "run-runtime-e2e.py",
                  "M26 LINUX RUNTIME DYNAMIC INTELLIGENCE VALIDATION SUCCESS")
    forbid("scripts/m26/run-final.ps1", windows, "gh workflow", "gh run", "workflow_dispatch")
    forbid("scripts/m26/run-final.sh", linux, "gh workflow", "gh run", "workflow_dispatch")

    execution = read("docs/project/roadmap/M26_EXECUTION.md")
    adr = read("docs/architecture/adr/DATA-0034-partial-runtime-observations-with-explicit-static-correlation.md")
    user = read("docs/user/runtime-intelligence.md")
    developer = read("docs/developer/runtime-dynamic-intelligence.md")
    for relative, document in (("docs/project/roadmap/M26_EXECUTION.md", execution),
                               ("docs/architecture/adr/DATA-0034-partial-runtime-observations-with-explicit-static-correlation.md", adr),
                               ("docs/user/runtime-intelligence.md", user),
                               ("docs/developer/runtime-dynamic-intelligence.md", developer)):
        require_facts(relative, document, "minos-runtime-observation-v1", "PARTIAL",
                      "OBSERVED_PARTIAL", "snapshot", "borné", "absence")
    require_facts("docs/project/roadmap/M26_EXECUTION.md", execution,
                  "#87", "#88", BASE, "M26-S9",
                  "M21-S2 / GitHub Actions reste **strictement en pause jusqu’en août 2026**")
    require_facts("docs/architecture/adr/DATA-0034-partial-runtime-observations-with-explicit-static-correlation.md", adr,
                  "Partial runtime observations with explicit static correlation",
                  "NOT_OBSERVED_IN_SELECTED_PARTIAL_SESSIONS")

    quality = read("scripts/quality/check-jacoco.py")
    require_facts("scripts/quality/check-jacoco.py", quality, '"m26-runtime-dynamic-intelligence"',
                  "FileRuntimeObservationStore", "RuntimeCommand")
    return "M26 RUNTIME DYNAMIC CONSISTENCY SUCCESS"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parser.add_argument("--self-test", action="store_true",
                        help="refuse one in-memory mutation per asserted fact, then exit")
    if parser.parse_args(argv).self_test:
        return literal_gate.self_test(ROOT, evaluate, STRUCTURED_MUTATIONS)
    return literal_gate.run(ROOT, evaluate, "M26 RUNTIME DYNAMIC CONSISTENCY FAILED")


if __name__ == "__main__":
    raise SystemExit(main())
