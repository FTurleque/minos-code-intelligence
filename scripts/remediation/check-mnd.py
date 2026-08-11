#!/usr/bin/env python3
"""Fail-closed static regression gate for the MND-01..MND-17 remediation campaign."""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        raise RuntimeError(f"missing MND evidence file: {relative}")
    return path.read_text(encoding="utf-8")


def require(relative: str, text: str, *needles: str) -> None:
    for needle in needles:
        if needle not in text:
            raise RuntimeError(f"{relative}: missing MND invariant: {needle}")


def forbid(relative: str, text: str, *needles: str) -> None:
    for needle in needles:
        if needle in text:
            raise RuntimeError(f"{relative}: forbidden MND regression: {needle}")


def main() -> int:
    try:
        remote = read("minos-cli/src/main/java/com/minos/cli/LocalRemoteIndexOperations.java")
        remote_lease = read("minos-cli/src/main/java/com/minos/cli/RemoteIndexLease.java")
        linux = read("minos-runtime-local/src/main/java/com/minos/runtime/LinuxBubblewrapWorkerSandboxBackend.java")
        windows = read("minos-runtime-local/src/main/java/com/minos/runtime/WindowsAppContainerWorkerSandboxBackend.java")
        windows_script = read("minos-runtime-local/src/main/resources/com/minos/runtime/windows-appcontainer-sandbox-v4.ps1")
        snapshots = read("minos-storage-local/src/main/java/com/minos/store/FileSymbolSnapshotStore.java")
        retention = read("minos-storage-local/src/main/java/com/minos/store/SnapshotRetentionService.java")
        runtime_codec = read("minos-application/src/main/java/com/minos/dynamic/RuntimeObservationEnvelopeCodec.java")
        graph_sidecar = read("minos-application/src/main/java/com/minos/program/analysis/FileProgramGraphProvider.java")
        hosted = read("minos-storage-local/src/main/java/com/minos/store/FileHostedControlPlaneStore.java")
        local_storage = read("minos-application/src/main/java/com/minos/storage/LocalStorageBackend.java")
        semantic_budget = read("minos-application/src/main/java/com/minos/semantic/SemanticIndexBudget.java")
        postgres = read("minos-storage-postgresql/src/main/java/com/minos/storage/postgresql/PostgresCodeKnowledgeSnapshotStore.java")
        graph_service = read("minos-application/src/main/java/com/minos/program/analysis/ProgramGraphService.java")
        fingerprints = read("minos-application/src/main/java/com/minos/program/analysis/FingerprintConstrainedJavaProgramGraphProvider.java")
        hybrid = read("minos-application/src/main/java/com/minos/semantic/HybridSearchService.java")
        polyglot = read("minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ManagedPolyglotScipRuntimeManager.java")
        discovery = read("minos-application/src/main/java/com/minos/discovery/ProjectIgnorePolicy.java")
        plugins = read("minos-application/src/main/java/com/minos/discovery/DefaultDiscoveryPlugins.java")
        registry = read("minos-application/src/main/java/com/minos/registry/InterProcessLocalProjectRegistry.java")
        coursier = read("minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ManagedScipProviderRuntimeManager.java")
        mcp_server = read("minos-mcp/src/main/java/com/minos/mcp/MinosMcpServer.java")

        require("LocalRemoteIndexOperations.java", remote,
                "RemoteIndexLease.acquire(application.home(), source.cacheKey())",
                "indexUnderSourceLease")
        require("RemoteIndexLease.java", remote_lease, "FileLock", "LOCK_STRIPES", "channel.lock()")

        require("LinuxBubblewrapWorkerSandboxBackend.java", linux,
                "addReadOnlyFile", "managedRuntimeRoot", "LINUX_EXACT_FILE_BIND_FOR_EXTERNAL_PROVIDER_ARGUMENTS")
        forbid("LinuxBubblewrapWorkerSandboxBackend.java", linux,
               "Files.isDirectory(real) ? real : real.getParent()")
        require("WindowsAppContainerWorkerSandboxBackend.java", windows,
                "windows-appcontainer-sandbox-v4.ps1", 'appendList(lines, "readFile"', 'lines.add("recovery="')
        require("windows-appcontainer-sandbox-v4.ps1", windows_script,
                "Grant-AppContainerFile", "Recover-Stale", "Write-Recovery", "readFile")

        require("FileSymbolSnapshotStore.java", snapshots,
                "SnapshotProjectLease.acquire(storageRoot, snapshot.projectId())")
        require("SnapshotRetentionService.java", retention,
                "SnapshotProjectLease.acquire(repository.storageRoot(), projectId)")

        require("RuntimeObservationEnvelopeCodec.java", runtime_codec, "BoundedLineReader", "FixedTsv.splitExact")
        forbid("RuntimeObservationEnvelopeCodec.java", runtime_codec, 'split("\\t", -1)')
        require("FileProgramGraphProvider.java", graph_sidecar,
                "BoundedInputStream", "BoundedLineReader", "FixedTsv.splitExact")
        forbid("FileProgramGraphProvider.java", graph_sidecar, 'split("\\t", -1)')

        require("FileHostedControlPlaneStore.java", hosted, "ReentrantLock[] JVM_LOCKS", "jvmLock.lock()")
        require("LocalStorageBackend.java", local_storage, "SerializedRuntimeObservationStore")

        require("SemanticIndexBudget.java", semantic_budget, "Double.BYTES")
        forbid("SemanticIndexBudget.java", semantic_budget, "Float.BYTES")

        require("PostgresCodeKnowledgeSnapshotStore.java", postgres,
                "activeMetadata(projectId)", "queryCache", "MAX_QUERY_CACHE_WEIGHT_BYTES", "buildLocks")
        require("ProgramGraphService.java", graph_service,
                "WeightedGraph", "evidenceWeight", "stringWeight", "maximumWeight")
        require("FingerprintConstrainedJavaProgramGraphProvider.java", fingerprints,
                "DEFAULT_MAX_FINGERPRINT_CACHE_WEIGHT_BYTES", "trimCache", "fingerprintCacheStats")
        require("HybridSearchService.java", hybrid,
                "DEFAULT_MAX_CORPUS_CACHE_WEIGHT_BYTES", "trimCorpusCache", "MAX_QUERY_UTF8_BYTES")

        require("ManagedPolyglotScipRuntimeManager.java", polyglot,
                "GOSUMDB", "sum.golang.org", "<clear/>", "INTEGRITY_MARKER", "directoryDigest")
        require("ProjectIgnorePolicy.java", discovery,
                "visibleFileNamesByRoot", "containsVisibleExtension", "scanVisibleFileNames")
        require("DefaultDiscoveryPlugins.java", plugins, "ignorePolicy.containsVisibleExtension")
        require("InterProcessLocalProjectRegistry.java", registry, "ReentrantLock[] JVM_LOCKS", "JVM_LOCK_STRIPES")
        forbid("InterProcessLocalProjectRegistry.java", registry, "ConcurrentMap<Path, ReentrantLock>")

        require("ManagedScipProviderRuntimeManager.java", coursier,
                "MAX_COURSIER_ARCHIVE_BYTES", "BodyHandlers.ofInputStream()", "BoundedInputStream")
        forbid("ManagedScipProviderRuntimeManager.java", coursier, "BodyHandlers.ofFile(archivePartial)")

        require("MinosMcpServer.java", mcp_server,
                "MAX_INBOUND_MESSAGE_BYTES", "currentMessageBytes",
                "MCP inbound message exceeds byte limit")

        print("MND REMEDIATION INVARIANTS SUCCESS")
        return 0
    except Exception as exception:
        print(f"MND REMEDIATION INVARIANTS FAILED: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
