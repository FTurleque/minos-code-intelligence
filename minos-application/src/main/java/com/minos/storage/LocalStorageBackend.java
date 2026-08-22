package com.minos.storage;

import com.minos.dynamic.RuntimeObservationStore;
import com.minos.incremental.FileProjectFingerprintSnapshotStore;
import com.minos.incremental.ProjectFingerprintSnapshotStore;
import com.minos.io.DurableAtomicFile;
import com.minos.orchestration.FileIndexStateStore;
import com.minos.orchestration.IndexStateStore;
import com.minos.registry.InterProcessLocalProjectRegistry;
import com.minos.registry.ProjectRegistry;
import com.minos.semantic.SemanticVectorStore;
import com.minos.store.CodeKnowledgeSnapshotStore;
import com.minos.store.FileRuntimeObservationStore;
import com.minos.store.FileSemanticVectorStore;
import com.minos.store.FileSymbolSnapshotStore;
import com.minos.store.ProjectMutationSemanticVectorStore;

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
        DurableAtomicFile.ensureDirectory(root, "local storage home");

        Path registryRoot = namespace(root, "registry");
        Path knowledgeRoot = namespace(root, "symbol-snapshots");
        Path indexRoot = namespace(root, "index-state");
        Path fingerprintRoot = namespace(root, "fingerprint-snapshots");
        Path semanticRoot = namespace(root, "semantic-index");
        Path runtimeRoot = namespace(root, "runtime-observations");

        this.projectRegistry = new InterProcessLocalProjectRegistry(registryRoot);
        FileIndexStateStore fileIndexState = new FileIndexStateStore(indexRoot);
        FileProjectFingerprintSnapshotStore fileFingerprints =
                new FileProjectFingerprintSnapshotStore(fingerprintRoot);
        this.snapshotStore = new FileSymbolSnapshotStore(knowledgeRoot);
        this.indexStateStore = fileIndexState;
        this.fingerprintStore = fileFingerprints;
        this.semanticVectorStore = new ProjectMutationSemanticVectorStore(
                semanticRoot, new FileSemanticVectorStore(semanticRoot));
        this.runtimeObservationStore = new SerializedRuntimeObservationStore(
                new FileRuntimeObservationStore(runtimeRoot));
        this.retentionService = new LocalStorageRetentionService(
                root, knowledgeRoot, indexRoot, fileFingerprints, fileIndexState);
    }

    private static Path namespace(Path root, String name) throws IOException {
        Path directory = root.resolve(name).toAbsolutePath().normalize();
        if (!directory.getParent().equals(root)) {
            throw new IOException("local storage namespace escapes MINOS home: " + name);
        }
        DurableAtomicFile.ensureDirectory(directory, "local storage namespace " + name);
        return directory;
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
