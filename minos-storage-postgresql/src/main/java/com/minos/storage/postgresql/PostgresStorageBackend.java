package com.minos.storage.postgresql;

import com.minos.dynamic.RuntimeObservationStore;
import com.minos.incremental.ProjectFingerprintSnapshotStore;
import com.minos.orchestration.IndexStateStore;
import com.minos.registry.ProjectRegistry;
import com.minos.semantic.SemanticVectorStore;
import com.minos.storage.StorageBackend;
import com.minos.storage.StorageRetentionService;
import com.minos.store.CodeKnowledgeSnapshotStore;

import java.io.IOException;
import java.nio.file.Path;

final class PostgresStorageBackend implements StorageBackend {
    private final PostgresConnectionFactory connections;
    private final ProjectRegistry projectRegistry;
    private final CodeKnowledgeSnapshotStore snapshotStore;
    private final IndexStateStore indexStateStore;
    private final ProjectFingerprintSnapshotStore fingerprintStore;
    private final SemanticVectorStore semanticVectorStore;
    private final RuntimeObservationStore runtimeObservationStore;
    private final StorageRetentionService retentionService;

    PostgresStorageBackend(PostgresConnectionFactory connections, Path home) throws IOException {
        this.connections = connections;
        new PostgresSchemaMigrator(connections).migrate();
        PostgresJsonCodec json = new PostgresJsonCodec();
        this.projectRegistry = new PostgresProjectRegistry(connections, home);
        this.snapshotStore = new PostgresCodeKnowledgeSnapshotStore(connections);
        this.indexStateStore = new PostgresIndexStateStore(connections, json);
        this.fingerprintStore = new PostgresFingerprintSnapshotStore(connections, json);
        this.semanticVectorStore = new PostgresSemanticVectorStore(connections);
        this.runtimeObservationStore = new PostgresRuntimeObservationStore(connections, json);
        this.retentionService = new PostgresStorageRetentionService(connections);
    }

    @Override public String id() { return "postgresql"; }
    @Override public ProjectRegistry projectRegistry() { return projectRegistry; }
    @Override public CodeKnowledgeSnapshotStore snapshotStore() { return snapshotStore; }
    @Override public IndexStateStore indexStateStore() { return indexStateStore; }
    @Override public ProjectFingerprintSnapshotStore fingerprintStore() { return fingerprintStore; }
    @Override public SemanticVectorStore semanticVectorStore() { return semanticVectorStore; }
    @Override public RuntimeObservationStore runtimeObservationStore() { return runtimeObservationStore; }
    @Override public StorageRetentionService retentionService() { return retentionService; }

    @Override
    public void close() {
        connections.close();
    }
}
