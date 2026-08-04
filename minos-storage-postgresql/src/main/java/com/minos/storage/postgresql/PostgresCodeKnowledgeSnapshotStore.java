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
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(projectId, requireText(snapshotId), ordered, List.of(), List.of());
        publishSnapshot(snapshot);
        return new SymbolSnapshot(projectId, snapshotId, ordered);
    }

    @Override
    public CodeKnowledgeSnapshot publish(UUID projectId, String snapshotId, Collection<Symbol> symbols,
                                         Collection<SymbolOccurrence> occurrences, Collection<Relationship> relationships) throws IOException {
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(projectId, requireText(snapshotId),
                symbols.stream().sorted(Comparator.comparing(Symbol::id)).toList(),
                occurrences.stream().sorted(Comparator.comparing(SymbolOccurrence::id)).toList(),
                relationships.stream().sorted(Comparator.comparing(Relationship::id)).toList());
        publishSnapshot(snapshot);
        return snapshot;
    }

    @Override
    public Optional<SymbolSnapshot> loadActive(UUID projectId) throws IOException {
        return loadActiveKnowledge(projectId).map(value -> new SymbolSnapshot(value.projectId(), value.snapshotId(), value.symbols()));
    }

    @Override
    public Optional<CodeKnowledgeSnapshot> loadActiveKnowledge(UUID projectId) throws IOException {
        Optional<Row> row = activeRow(projectId);
        if (row.isEmpty()) return Optional.empty();
        return Optional.of(decodeVerified(projectId, row.orElseThrow()));
    }

    @Override
    public Optional<SnapshotQueryView> loadActiveQueryView(UUID projectId) throws IOException {
        Optional<Row> row = activeRow(projectId);
        if (row.isEmpty()) return Optional.empty();
        Row value = row.orElseThrow();
        CodeKnowledgeSnapshot snapshot = decodeVerified(projectId, value);
        long started = System.nanoTime();
        InMemoryCodeKnowledgeStore queryStore = new InMemoryCodeKnowledgeStore(snapshot);
        long buildNanos = System.nanoTime() - started;
        SnapshotDescriptor descriptor = new SnapshotDescriptor(2, snapshot.snapshotId(),
                "postgresql:" + snapshot.snapshotId(), value.sha256(), snapshot.symbols().size(),
                snapshot.occurrences().size(), snapshot.relationships().size());
        return Optional.of(new SnapshotQueryView(descriptor, snapshot, queryStore, buildNanos));
    }

    private void publishSnapshot(CodeKnowledgeSnapshot snapshot) throws IOException {
        byte[] payload = codec.encode(snapshot);
        String sha = sha256(payload);
        try (Connection c = connections.open()) {
            c.setAutoCommit(false);
            try {
                String existingSha = null;
                try (PreparedStatement q = c.prepareStatement("SELECT sha256 FROM knowledge_snapshots WHERE project_id=? AND snapshot_id=?")) {
                    q.setObject(1, snapshot.projectId()); q.setString(2, snapshot.snapshotId());
                    try (ResultSet r = q.executeQuery()) { if (r.next()) existingSha = r.getString(1); }
                }
                if (existingSha != null && !existingSha.equals(sha)) {
                    throw new IOException("PostgreSQL snapshot identity already exists with different content: " + snapshot.snapshotId());
                }
                if (existingSha == null) {
                    try (PreparedStatement s = c.prepareStatement("INSERT INTO knowledge_snapshots(project_id,snapshot_id,payload,sha256,symbol_count,occurrence_count,relationship_count) VALUES (?,?,?,?,?,?,?)")) {
                        s.setObject(1, snapshot.projectId()); s.setString(2, snapshot.snapshotId()); s.setBytes(3, payload); s.setString(4, sha);
                        s.setInt(5, snapshot.symbols().size()); s.setInt(6, snapshot.occurrences().size()); s.setInt(7, snapshot.relationships().size()); s.executeUpdate();
                    }
                }
                try (PreparedStatement s = c.prepareStatement("INSERT INTO knowledge_active(project_id,snapshot_id) VALUES (?,?) ON CONFLICT(project_id) DO UPDATE SET snapshot_id=EXCLUDED.snapshot_id")) {
                    s.setObject(1, snapshot.projectId()); s.setString(2, snapshot.snapshotId()); s.executeUpdate();
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                if (e instanceof IOException io) throw io;
                throw new IOException("unable to publish PostgreSQL knowledge snapshot", e);
            }
        } catch (SQLException e) { throw new IOException("unable to publish PostgreSQL knowledge snapshot", e); }
    }

    private Optional<Row> activeRow(UUID projectId) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        try (Connection c = connections.open(); PreparedStatement s = c.prepareStatement(
                "SELECT s.snapshot_id,s.payload,s.sha256 FROM knowledge_active a JOIN knowledge_snapshots s ON s.project_id=a.project_id AND s.snapshot_id=a.snapshot_id WHERE a.project_id=?")) {
            s.setObject(1, projectId);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) return Optional.empty();
                return Optional.of(new Row(r.getString(1), r.getBytes(2), r.getString(3)));
            }
        } catch (SQLException e) { throw new IOException("unable to load PostgreSQL knowledge snapshot", e); }
    }

    private CodeKnowledgeSnapshot decodeVerified(UUID projectId, Row row) throws IOException {
        if (!row.sha256().equals(sha256(row.payload()))) throw new IOException("PostgreSQL knowledge snapshot checksum mismatch");
        CodeKnowledgeSnapshot snapshot = codec.decode(row.payload());
        if (!projectId.equals(snapshot.projectId()) || !row.snapshotId().equals(snapshot.snapshotId())) {
            throw new IOException("PostgreSQL knowledge snapshot identity mismatch");
        }
        return snapshot;
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("snapshotId must not be blank");
        return value;
    }

    private record Row(String snapshotId, byte[] payload, String sha256) {
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Row r)) return false;
            return Objects.equals(snapshotId, r.snapshotId) && Arrays.equals(payload, r.payload) && Objects.equals(sha256, r.sha256);
        }
        @Override public int hashCode() { return Objects.hash(snapshotId, Arrays.hashCode(payload), sha256); }
        @Override public String toString() { return "Row[snapshotId=" + snapshotId + ", payload=byte[" + payload.length + "], sha256=" + sha256 + "]"; }
    }
}
