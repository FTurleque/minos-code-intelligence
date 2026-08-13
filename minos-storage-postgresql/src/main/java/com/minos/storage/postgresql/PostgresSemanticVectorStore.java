package com.minos.storage.postgresql;

import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore;
import com.minos.semantic.StaleSemanticSyncException;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** pgvector store whose reads and writes remain aligned with one structural snapshot. */
final class PostgresSemanticVectorStore implements SemanticVectorStore {
    private final PostgresConnectionFactory connections;

    PostgresSemanticVectorStore(PostgresConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    @Override public String searchEngine() { return "pgvector-exact-cosine"; }

    @Override
    public Optional<IndexMetadata> metadata(String projectId) throws IOException {
        UUID id = uuid(projectId);
        try {
            return connections.withConnection(connection -> PostgresSemanticReadQueries.metadata(connection, projectId, id));
        } catch (SQLException exception) {
            throw new IOException("unable to load pgvector semantic metadata", exception);
        }
    }

    @Override
    public Optional<IndexSnapshot> load(String projectId) throws IOException {
        UUID id = uuid(projectId);
        try {
            return connections.withConnection(connection -> PostgresSemanticReadQueries.load(connection, projectId, id));
        } catch (SQLException exception) {
            throw new IOException("unable to load pgvector semantic index", exception);
        }
    }

    @Override
    public void replace(IndexSnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        UUID projectId = uuid(snapshot.projectId());
        try {
            connections.inTransaction(connection -> {
                PostgresProjectMutationLock.acquire(connection, projectId);
                PostgresSemanticWriteQueries.replace(connection, projectId, snapshot);
                return null;
            });
        } catch (SQLException exception) {
            throw new IOException("unable to replace pgvector semantic index", exception);
        }
    }

    @Override
    public void replaceConditionally(IndexSnapshot next, String expectedActiveSnapshotId,
                                     ActiveSnapshotIdReader activeSnapshotReader) throws IOException {
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(activeSnapshotReader, "activeSnapshotReader");
        if (expectedActiveSnapshotId == null || expectedActiveSnapshotId.isBlank()) {
            throw new IllegalArgumentException("expectedActiveSnapshotId must not be blank");
        }
        UUID projectId = uuid(next.projectId());
        try {
            connections.inTransaction(connection -> {
                PostgresProjectMutationLock.acquire(connection, projectId);
                // PostgreSQL must re-read the authoritative structural pointer on this exact JDBC
                // transaction. Calling the external reader here would borrow a second pooled
                // connection and can deadlock a saturated pool while the advisory locks are held.
                Optional<String> current = PostgresSemanticReadQueries.activeKnowledgeSnapshotId(connection, projectId);
                String currentId = current.orElse(null);
                if (!expectedActiveSnapshotId.equals(currentId)) {
                    throw new StaleSemanticSyncException(next.projectId(), expectedActiveSnapshotId,
                            currentId == null ? "absent" : currentId);
                }
                PostgresSemanticWriteQueries.replace(connection, projectId, next);
                return null;
            });
        } catch (StaleSemanticSyncException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw new IOException("unable to conditionally replace pgvector semantic index", exception);
        }
    }

    @Override
    public void delete(String projectId) throws IOException {
        UUID id = uuid(projectId);
        try {
            connections.inTransaction(connection -> {
                PostgresProjectMutationLock.acquire(connection, id);
                PostgresSemanticWriteQueries.delete(connection, id);
                return null;
            });
        } catch (SQLException exception) {
            throw new IOException("unable to delete pgvector semantic index", exception);
        }
    }

    @Override
    public List<VectorHit> search(String projectId, SemanticVector query, int limit, double minimumScore)
            throws IOException {
        Objects.requireNonNull(query, "query");
        if (limit < 1 || limit > 1_000) throw new IllegalArgumentException("limit must be between 1 and 1000");
        if (!Double.isFinite(minimumScore) || minimumScore < -1.0 || minimumScore > 1.0) {
            throw new IllegalArgumentException("minimumScore must be between -1 and 1");
        }
        UUID id = uuid(projectId);
        try {
            return connections.withConnection(connection ->
                    PostgresSemanticReadQueries.search(connection, projectId, id, query, limit, minimumScore));
        } catch (SQLException exception) {
            throw new IOException("unable to search pgvector semantic index", exception);
        }
    }

    @Override
    public long sizeBytes(String projectId) throws IOException {
        UUID id = uuid(projectId);
        try {
            return connections.withConnection(connection -> PostgresSemanticReadQueries.sizeBytes(connection, id));
        } catch (SQLException exception) {
            throw new IOException("unable to measure pgvector semantic index", exception);
        }
    }

    private static UUID uuid(String projectId) throws IOException {
        try {
            return UUID.fromString(projectId);
        } catch (IllegalArgumentException exception) {
            throw new IOException("semantic project id must be a UUID", exception);
        }
    }
}
