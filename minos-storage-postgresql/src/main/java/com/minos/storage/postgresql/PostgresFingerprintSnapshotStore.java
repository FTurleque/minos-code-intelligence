package com.minos.storage.postgresql;

import com.minos.incremental.ProjectFingerprint;
import com.minos.incremental.ProjectFingerprintSnapshot;
import com.minos.incremental.ProjectFingerprintSnapshotStore;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class PostgresFingerprintSnapshotStore implements ProjectFingerprintSnapshotStore {
    private final PostgresConnectionFactory connections;
    private final PostgresJsonCodec json;

    PostgresFingerprintSnapshotStore(PostgresConnectionFactory connections, PostgresJsonCodec json) {
        this.connections = connections; this.json = json;
    }

    @Override
    public ProjectFingerprintSnapshot publish(UUID projectId, String indexSnapshotId, ProjectFingerprint fingerprint) throws IOException {
        ProjectFingerprintSnapshot snapshot = new ProjectFingerprintSnapshot(projectId, indexSnapshotId, fingerprint);
        String payload = json.write(snapshot);
        try (Connection c = connections.open()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement q = c.prepareStatement("SELECT payload::text FROM fingerprint_snapshots WHERE project_id=? AND snapshot_id=?")) {
                    q.setObject(1, projectId); q.setString(2, indexSnapshotId);
                    try (ResultSet r = q.executeQuery()) {
                        if (r.next()) {
                            ProjectFingerprintSnapshot existing = json.read(r.getString(1), ProjectFingerprintSnapshot.class);
                            if (!existing.equals(snapshot)) throw new IOException("fingerprint snapshot already exists with different content: " + indexSnapshotId);
                            c.rollback(); return existing;
                        }
                    }
                }
                try (PreparedStatement s = c.prepareStatement("INSERT INTO fingerprint_snapshots(project_id,snapshot_id,payload) VALUES (?,?,CAST(? AS jsonb))")) {
                    s.setObject(1, projectId); s.setString(2, indexSnapshotId); s.setString(3, payload); s.executeUpdate();
                }
                c.commit(); return snapshot;
            } catch (Exception e) {
                c.rollback();
                if (e instanceof IOException io) throw io;
                throw new IOException("unable to publish PostgreSQL fingerprint snapshot", e);
            }
        } catch (SQLException e) { throw new IOException("unable to publish PostgreSQL fingerprint snapshot", e); }
    }

    @Override
    public void promote(UUID projectId, String indexSnapshotId) throws IOException {
        if (load(projectId, indexSnapshotId).isEmpty()) throw new IOException("fingerprint snapshot is not published: " + indexSnapshotId);
        try (Connection c = connections.open(); PreparedStatement s = c.prepareStatement(
                "INSERT INTO fingerprint_active(project_id,snapshot_id) VALUES (?,?) ON CONFLICT(project_id) DO UPDATE SET snapshot_id=EXCLUDED.snapshot_id")) {
            s.setObject(1, projectId); s.setString(2, indexSnapshotId); s.executeUpdate();
        } catch (SQLException e) { throw new IOException("unable to promote PostgreSQL fingerprint snapshot", e); }
    }

    @Override
    public Optional<ProjectFingerprintSnapshot> load(UUID projectId, String indexSnapshotId) throws IOException {
        try (Connection c = connections.open(); PreparedStatement s = c.prepareStatement(
                "SELECT payload::text FROM fingerprint_snapshots WHERE project_id=? AND snapshot_id=?")) {
            s.setObject(1, projectId); s.setString(2, indexSnapshotId);
            try (ResultSet r = s.executeQuery()) { return r.next() ? Optional.of(json.read(r.getString(1), ProjectFingerprintSnapshot.class)) : Optional.empty(); }
        } catch (SQLException e) { throw new IOException("unable to load PostgreSQL fingerprint snapshot", e); }
    }

    @Override
    public Optional<ProjectFingerprintSnapshot> loadActive(UUID projectId) throws IOException {
        try (Connection c = connections.open(); PreparedStatement s = c.prepareStatement(
                "SELECT s.payload::text FROM fingerprint_active a JOIN fingerprint_snapshots s ON s.project_id=a.project_id AND s.snapshot_id=a.snapshot_id WHERE a.project_id=?")) {
            s.setObject(1, projectId);
            try (ResultSet r = s.executeQuery()) { return r.next() ? Optional.of(json.read(r.getString(1), ProjectFingerprintSnapshot.class)) : Optional.empty(); }
        } catch (SQLException e) { throw new IOException("unable to load active PostgreSQL fingerprint snapshot", e); }
    }

    @Override
    public List<String> listIndexSnapshotIds(UUID projectId) throws IOException {
        List<String> ids = new ArrayList<>();
        try (Connection c = connections.open(); PreparedStatement s = c.prepareStatement(
                "SELECT snapshot_id FROM fingerprint_snapshots WHERE project_id=? ORDER BY snapshot_id")) {
            s.setObject(1, projectId); try (ResultSet r = s.executeQuery()) { while (r.next()) ids.add(r.getString(1)); }
            return List.copyOf(ids);
        } catch (SQLException e) { throw new IOException("unable to list PostgreSQL fingerprint snapshots", e); }
    }
}
