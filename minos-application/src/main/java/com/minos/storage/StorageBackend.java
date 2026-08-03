package com.minos.storage;

import com.minos.dynamic.RuntimeObservationStore;
import com.minos.incremental.ProjectFingerprintSnapshotStore;
import com.minos.orchestration.IndexStateStore;
import com.minos.registry.ProjectRegistry;
import com.minos.semantic.SemanticVectorStore;
import com.minos.store.CodeKnowledgeSnapshotStore;

/** Complete persistence backend used by one MINOS application instance. */
public interface StorageBackend extends AutoCloseable {

    String id();

    ProjectRegistry projectRegistry();

    CodeKnowledgeSnapshotStore snapshotStore();

    IndexStateStore indexStateStore();

    ProjectFingerprintSnapshotStore fingerprintStore();

    SemanticVectorStore semanticVectorStore();

    RuntimeObservationStore runtimeObservationStore();

    @Override
    default void close() throws Exception {
        // Most backends are connection-per-operation or file-based and need no close action.
    }
}
