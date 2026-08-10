#!/usr/bin/env python3
"""Fail-closed invariants for the MNE-01..MNE-17 remediation campaign."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        raise RuntimeError(f"missing MNE evidence file: {relative}")
    return path.read_text(encoding="utf-8")


def require(relative: str, text: str, *needles: str) -> None:
    for needle in needles:
        if needle not in text:
            raise RuntimeError(f"{relative}: missing MNE invariant: {needle}")


def forbid(relative: str, text: str, *needles: str) -> None:
    for needle in needles:
        if needle in text:
            raise RuntimeError(f"{relative}: forbidden MNE regression: {needle}")


def main() -> int:
    try:
        compaction = read("minos-storage-local/src/main/java/com/minos/store/SnapshotCompactionService.java")
        retention = read("minos-storage-local/src/main/java/com/minos/store/SnapshotRetentionService.java")
        postgres_snapshot = read("minos-storage-postgresql/src/main/java/com/minos/storage/postgresql/PostgresCodeKnowledgeSnapshotStore.java")
        snapshot_codec = read("minos-storage-local/src/main/java/com/minos/store/SnapshotBinaryCodecSupport.java")
        file_snapshot = read("minos-storage-local/src/main/java/com/minos/store/FileSymbolSnapshotStore.java")
        semantic_budget = read("minos-application/src/main/java/com/minos/semantic/SemanticIndexBudget.java")
        semantic_service = read("minos-application/src/main/java/com/minos/semantic/SemanticIndexService.java")
        semantic_store = read("minos-storage-local/src/main/java/com/minos/store/FileSemanticVectorStore.java")
        scip = read("minos-provider-scip/src/main/java/com/minos/adapter/scip/ScipIngestionLimits.java")
        discovery = read("minos-application/src/main/java/com/minos/discovery/ProjectDiscoveryService.java")
        fingerprint = read("minos-application/src/main/java/com/minos/program/analysis/FingerprintConstrainedJavaProgramGraphProvider.java")
        runtime_port = read("minos-engine/src/main/java/com/minos/dynamic/RuntimeObservationStore.java")
        runtime_service = read("minos-application/src/main/java/com/minos/dynamic/RuntimeIntelligenceService.java")
        local_runtime = read("minos-storage-local/src/main/java/com/minos/store/FileRuntimeObservationStore.java")
        postgres_runtime = read("minos-storage-postgresql/src/main/java/com/minos/storage/postgresql/PostgresRuntimeObservationStore.java")
        provider_api = read("minos-api/src/main/java/com/minos/api/LocalProviderPlatformApi.java")
        multi_api = read("minos-api/src/main/java/com/minos/api/LocalMinosMultiRepositoryApi.java")
        nexus = read("minos-nexus/src/main/java/com/minos/integration/nexus/NexusExportService.java")
        hybrid = read("minos-application/src/main/java/com/minos/semantic/HybridSearchService.java")
        token = read("minos-application/src/main/java/com/minos/context/TokenEstimator.java")
        polyglot = read("minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ManagedPolyglotScipRuntimeManager.java")
        windows = read("minos-runtime-local/src/main/java/com/minos/runtime/WindowsAppContainerWorkerSandboxBackend.java")

        # MNE-01: read active pointer under the same project lease as retention deletion.
        require("SnapshotCompactionService.java", compaction,
                "SnapshotProjectLease.acquire(storageRoot, projectId)",
                "activeSnapshots.read(projectId)",
                "retention.applyPolicyLocked")
        if compaction.index("SnapshotProjectLease.acquire") > compaction.index("activeSnapshots.read(projectId)"):
            raise RuntimeError("SnapshotCompactionService.java: active pointer is still read before project lease")
        require("SnapshotRetentionService.java", retention, "RetentionResult applyPolicyLocked(")

        # MNE-02: bounded, iterative PostgreSQL active resolution with explicit stale temp cleanup.
        require("PostgresCodeKnowledgeSnapshotStore.java", postgres_snapshot,
                "MAX_ACTIVE_QUERY_RETRIES", "for (int attempt = 1; attempt <= MAX_ACTIVE_QUERY_RETRIES; attempt++)",
                "Files.deleteIfExists(value.payload())")
        forbid("PostgresCodeKnowledgeSnapshotStore.java", postgres_snapshot,
               "return loadActiveQueryView(projectId);")

        # MNE-03/04/06/07: real byte/working-set bounds and payload-aware cache weights.
        require("SemanticIndexBudget.java", semantic_budget,
                "192L * 1024L * 1024L")
        require("SemanticIndexService.java", semantic_service,
                "MAX_SYNCHRONIZE_WORKING_SET_BYTES", "estimateWorkingSet",
                "SEMANTIC_REUSE_SKIPPED_WORKING_SET_BUDGET")
        require("FileSemanticVectorStore.java", semantic_store,
                "MAX_INDEX_FILE_BYTES", "MAX_DECODED_STRING_BYTES", "MAX_DECODED_VECTOR_BYTES",
                "class DecodeBudget", "requireIndexFileSize", "Optional<IndexMetadata> metadata")
        forbid("FileSemanticVectorStore.java", semantic_store, "compact(indexed)")
        require("SnapshotBinaryCodecSupport.java", snapshot_codec,
                "MAX_PERSISTED_SNAPSHOT_BYTES = 256L * 1024L * 1024L", "requireSnapshotFileSize")
        require("FileSymbolSnapshotStore.java", file_snapshot,
                "QUERY_VIEW_PERSISTED_AMPLIFICATION", "Files.size(snapshot)", "safeMultiply")
        require("PostgresCodeKnowledgeSnapshotStore.java", postgres_snapshot,
                "QUERY_VIEW_PERSISTED_AMPLIFICATION", "estimateWeight(metadata, payloadBytes)")

        # MNE-05: preflight accounts length-delimited amplification before protobuf object materialization.
        require("ScipIngestionLimits.java", scip,
                "MAX_NESTED_MESSAGE_BYTES", "nestedMessageBytes", "accountNestedBytes")

        # MNE-08/09: actual traversal accounting and exact-length source hashing.
        require("ProjectDiscoveryService.java", discovery,
                "discoverModuleRoots(root, ignorePolicy, budget)", "budget.accountTraversalEntry()",
                "visitFile(Path file", "visitFileFailed(Path file")
        require("FingerprintConstrainedJavaProgramGraphProvider.java", fingerprint,
                "sha256Exact", "grew while being fingerprinted", "MAX_SECURITY_CONFIG_BYTES")
        forbid("FingerprintConstrainedJavaProgramGraphProvider.java", fingerprint,
               "private static String sha256(Path file)")

        # MNE-10: persistence applies filter/limit before full session materialization.
        require("RuntimeObservationStore.java", runtime_port,
                "list(UUID projectId, String snapshotId, int limit)")
        require("RuntimeIntelligenceService.java", runtime_service,
                "store.list(project.id(), null, limit)", "store.list(projectId, snapshotId, MAX_SESSION_RESULTS)")
        require("FileRuntimeObservationStore.java", local_runtime,
                "SessionMetadata", "readMetadata(file, projectId)")
        require("PostgresRuntimeObservationStore.java", postgres_runtime,
                "LIMIT ?", "payload->'session'->>'snapshotId'")

        # MNE-11: Path constructors own lifecycle; injected application constructors do not.
        require("LocalProviderPlatformApi.java", provider_api,
                "implements ProviderPlatformApi, AutoCloseable", "ownsApplication", "application.close()")
        require("LocalMinosMultiRepositoryApi.java", multi_api,
                "ownsApplication", "this(openApplication(home), true)", "application.close()")

        # MNE-12/13/14: weighted NEXUS selection, semantic generation identity, allocation-light tokenization.
        require("NexusExportService.java", nexus,
                "MAX_SYMBOL_SELECTION_WEIGHT_BYTES", "MAX_RELATION_SELECTION_WEIGHT_BYTES",
                "ToLongFunction<T>", "relationshipWeight")
        require("HybridSearchService.java", hybrid,
                "activeSemanticIndex.builtAtEpochMilli()", "corpusIdentity", "identity().equals(corpusIdentity)")
        require("TokenEstimator.java", token,
                "boundaryWithinUtf8Bytes", "utf8Bytes(int codePoint)")
        forbid("TokenEstimator.java", token, "getBytes(StandardCharsets.UTF_8)", "while (low < high)")

        # MNE-15/16: exact NuGet package pin and bounded managed-runtime integrity traversal.
        require("ManagedPolyglotScipRuntimeManager.java", polyglot,
                "DOTNET_PACKAGE_SHA256", "downloadPinnedDotnetPackage", "pinned-nuget-source",
                "MAX_MANAGED_TRAVERSAL_ENTRIES", "MAX_MANAGED_FILES", "MAX_MANAGED_BYTES")
        match = re.search(r'DOTNET_PACKAGE_SHA256 = "([0-9a-f]{64})"', polyglot)
        if not match:
            raise RuntimeError("ManagedPolyglotScipRuntimeManager.java: scip-dotnet SHA-256 is not immutable")
        forbid("ManagedPolyglotScipRuntimeManager.java", polyglot,
               'DOTNET_SOURCE = "https://api.nuget.org/v3/index.json"', ".sorted(Comparator.comparing(path -> portable(directory.relativize(path))))\n                    .toList()")

        # MNE-17: no read grant for the whole MINOS tools root on Windows.
        require("WindowsAppContainerWorkerSandboxBackend.java", windows, "managedRuntimeRoot(real, tools)")
        forbid("WindowsAppContainerWorkerSandboxBackend.java", windows,
               "addReadRoot(readRoots, tools)", "addReadRoot(roots, tools)")

        print("MNE REMEDIATION INVARIANTS SUCCESS")
        return 0
    except Exception as exception:
        print(f"MNE REMEDIATION INVARIANTS FAILED: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
