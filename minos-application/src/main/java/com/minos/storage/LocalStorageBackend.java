package com.minos.storage;

import com.minos.dynamic.RuntimeObservationStore;
import com.minos.incremental.FileProjectFingerprintSnapshotStore;
import com.minos.incremental.ProjectFingerprintSnapshotStore;
import com.minos.orchestration.FileIndexStateStore;
import com.minos.orchestration.IndexStateStore;
import com.minos.registry.InterProcessLocalProjectRegistry;
import com.minos.registry.ProjectRegistry;
import com.minos.semantic.SemanticVectorStore;
import com.minos.store.CodeKnowledgeSnapshotStore;
import com.minos.store.FileRuntimeObservationStore;
import com.minos.store.FileSemanticVectorStore;
import com.minos.store.FileSymbolSnapshotStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Historical file-backed MINOS storage backend. */
public final class LocalStorageBackend implements StorageBackend {
    private final ProjectRegistry projectRegistry;
    private final CodeKnowledgeSnapshotStore snapshotStore;
    private final IndexStateStore indexStateStore;
    private final ProjectFingerprintSnapshotStore fingerprintStore;
    private final SemanticVectorStore semanticVectorStore;
    private final RuntimeObservationStore runtimeObservationStore;
    private final StorageRetentionService retentionService;

    public LocalStorageBackend(Path home) throws IOException {
        Path root = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        this.projectRegistry = new InterProcessLocalProjectRegistry(root.resolve("registry"));
        Path knowledgeRoot = root.resolve("symbol-snapshots");
        Path indexRoot = root.resolve("index-state");
        FileIndexStateStore fileIndexState = new FileIndexStateStore(indexRoot);
        FileProjectFingerprintSnapshotStore fileFingerprints =
                new FileProjectFingerprintSnapshotStore(root.resolve("fingerprint-snapshots"));
        this.snapshotStore = new FileSymbolSnapshotStore(knowledgeRoot);
        this.indexStateStore = fileIndexState;
        this.fingerprintStore = fileFingerprints;
        this.semanticVectorStore = new FileSemanticVectorStore(root.resolve("semantic-index"));
        this.runtimeObservationStore = new SerializedRuntimeObservationStore(
                new FileRuntimeObservationStore(root.resolve("runtime-observations")));
        this.retentionService = new LocalStorageRetentionService(
                root, knowledgeRoot, indexRoot, fileFingerprints, fileIndexState);
    }

    @Override public String id() { return "local"; }
    @Override public ProjectRegistry projectRegistry() { return projectRegistry; }
    @Override public CodeKnowledgeSnapshotStore snapshotStore() { return snapshotStore; }
    @Override public IndexStateStore indexStateStore() { return indexStateStore; }
    @Override public ProjectFingerprintSnapshotStore fingerprintStore() { return fingerprintStore; }
    @Override public SemanticVectorStore semanticVectorStore() { return semanticVectorStore; }
    @Override public RuntimeObservationStore runtimeObservationStore() { return runtimeObservationStore; }
    @Override public StorageRetentionService retentionService() { return retentionService; }
}
