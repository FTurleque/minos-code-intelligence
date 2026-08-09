package com.minos.storage.postgresql;

import com.minos.domain.Relationship;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolOccurrence;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;
import com.minos.store.InMemoryCodeKnowledgeStore;
import com.minos.store.SnapshotDescriptor;
import com.minos.store.SnapshotQueryView;
import com.minos.store.SymbolSnapshot;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class PostgresCodeKnowledgeSnapshotStore implements CodeKnowledgeSnapshotStore {
    private final PostgresConnectionFactory connections;
    private final PostgresSnapshotPayloadCodec codec = new PostgresSnapshotPayloadCodec();

    PostgresCodeKnowledgeSnapshotStore(PostgresConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    @Override
    public SymbolSnapshot publish(UUID projectId, String snapshotId, Collection<Symbol> symbols) throws IOException {
        List<Symbol> ordered = symbols.stream().sorted(Comparator.comparing(Symbol::id)).toList();
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(
                projectId, requireText(snapshotId), ordered, List.of(), List.of());
        publishSnapshot(snapshot);
        return new SymbolSnapshot(projectId, snapshotId, ordered);
    }

    @Override
    public CodeKnowledgeSnapshot publish(
            UUID projectId,
            String snapshotId,
            Collection<Symbol> symbols,
            Collection<SymbolOccurrence> occurrences,
            Collection<Relationship> relationships
    ) throws IOException {
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(
                projectId,
                requireText(snapshotId),
                symbols.stream().sorted(Comparator.comparing(Symbol::id)).toList(),
                occurrences.stream().sorted(Comparator.comparing(SymbolOccurrence::id)).toList(),
                relationships.stream().sorted(Comparator.comparing(Relationship::id)).toList());
        publishSnapshot(snapshot);
        return snapshot;
    }

    @Override
    public Optional<SymbolSnapshot> loadActive(UUID projectId) throws IOException {
        return loadActiveKnowledge(projectId).map(value ->
                new SymbolSnapshot(value.projectId(), value.snapshotId(), value.symbols()));
    }

    @Override
    public Optional<CodeKnowledgeSnapshot> loadActiveKnowledge(UUID projectId) throws IOException {
        Optional<Row> row = activeRow(projectId);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(decodeVerified(projectId, row.orElseThrow()));
    }

    @Override
    public Optional<SnapshotQueryView> loadActiveQueryView(UUID projectId) throws IOException {
        Optional<Row> row = activeRow(projectId);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        Row value = row.orElseThrow();
        CodeKnowledgeSnapshot snapshot = decodeVerified(projectId, value);
        long started = System.nanoTime();
        InMemoryCodeKnowledgeStore queryStore = new InMemoryCodeKnowledgeStore(snapshot);
        long buildNanos = System.nanoTime() - started;
        SnapshotDescriptor descriptor = new SnapshotDescriptor(
                2,
                snapshot.snapshotId(),
                "postgresql:" + snapshot.snapshotId(),
                value.sha256(),
                snapshot.symbols().size(),
                snapshot.occurrences().size(),
                snapshot.relationships().size());
        return Optional.of(new SnapshotQueryView(descriptor, snapshot, queryStore, buildNanos));
    }

    private void publishSnapshot(CodeKnowledgeSnapshot snapshot) throws IOException {
        byte[] payload = codec.encode(snapshot);
        String sha = sha256(payload);
        try {
            connections.inTransaction(connection -> {
                String existingSha = existingSnapshotSha(connection, snapshot);
                validateExistingSnapshot(snapshot, sha, existingSha);
                if (existingSha == null) {
                    insertSnapshot(connection, snapshot, payload, sha);
                }
                activateSnapshot(connection, snapshot);
                return null;
            });
        } catch (SQLException exception) {
            throw new IOException("unable to publish PostgreSQL knowledge snapshot", exception);
        }
    }

    private static String existingSnapshotSha(Connection connection, CodeKnowledgeSnapshot snapshot)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT sha256 FROM knowledge_snapshots WHERE project_id=? AND snapshot_id=?")) {
            statement.setObject(1, snapshot.projectId());
            statement.setString(2, snapshot.snapshotId());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private static void validateExistingSnapshot(
            CodeKnowledgeSnapshot snapshot,
            String expectedSha,
            String existingSha
    ) throws IOException {
        if (existingSha != null && !existingSha.equals(expectedSha)) {
            throw new IOException(
                    "PostgreSQL snapshot identity already exists with different content: "
                            + snapshot.snapshotId());
        }
    }

    private static void insertSnapshot(
            Connection connection,
            CodeKnowledgeSnapshot snapshot,
            byte[] payload,
            String sha
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO knowledge_snapshots(project_id,snapshot_id,payload,sha256,symbol_count,"
                        + "occurrence_count,relationship_count) VALUES (?,?,?,?,?,?,?)")) {
            statement.setObject(1, snapshot.projectId());
            statement.setString(2, snapshot.snapshotId());
            statement.setBytes(3, payload);
            statement.setString(4, sha);
            statement.setInt(5, snapshot.symbols().size());
            statement.setInt(6, snapshot.occurrences().size());
            statement.setInt(7, snapshot.relationships().size());
            statement.executeUpdate();
        }
    }

    private static void activateSnapshot(Connection connection, CodeKnowledgeSnapshot snapshot) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO knowledge_active(project_id,snapshot_id) VALUES (?,?) "
                        + "ON CONFLICT(project_id) DO UPDATE SET snapshot_id=EXCLUDED.snapshot_id")) {
            statement.setObject(1, snapshot.projectId());
            statement.setString(2, snapshot.snapshotId());
            statement.executeUpdate();
        }
    }

    private Optional<Row> activeRow(UUID projectId) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        try {
            return connections.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT s.snapshot_id,s.payload,s.sha256 FROM knowledge_active a "
                                + "JOIN knowledge_snapshots s "
                                + "ON s.project_id=a.project_id AND s.snapshot_id=a.snapshot_id "
                                + "WHERE a.project_id=?")) {
                    statement.setObject(1, projectId);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            return Optional.empty();
                        }
                        return Optional.of(new Row(result.getString(1), result.getBytes(2), result.getString(3)));
                    }
                }
            });
        } catch (SQLException exception) {
            throw new IOException("unable to load PostgreSQL knowledge snapshot", exception);
        }
    }

    private CodeKnowledgeSnapshot decodeVerified(UUID projectId, Row row) throws IOException {
        if (!row.sha256().equals(sha256(row.payload()))) {
            throw new IOException("PostgreSQL knowledge snapshot checksum mismatch");
        }
        CodeKnowledgeSnapshot snapshot = codec.decode(row.payload());
        if (!projectId.equals(snapshot.projectId()) || !row.snapshotId().equals(snapshot.snapshotId())) {
            throw new IOException("PostgreSQL knowledge snapshot identity mismatch");
        }
        return snapshot;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("snapshotId must not be blank");
        }
        return value;
    }

    private record Row(String snapshotId, byte[] payload, String sha256) {
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Row row)) {
                return false;
            }
            return Objects.equals(snapshotId, row.snapshotId)
                    && Arrays.equals(payload, row.payload)
                    && Objects.equals(sha256, row.sha256);
        }

        @Override
        public int hashCode() {
            return Objects.hash(snapshotId, Arrays.hashCode(payload), sha256);
        }

        @Override
        public String toString() {
            return "Row[snapshotId=" + snapshotId + ", payload=byte[" + payload.length + "]"
                    + ", sha256=" + sha256 + "]";
        }
    }
}
