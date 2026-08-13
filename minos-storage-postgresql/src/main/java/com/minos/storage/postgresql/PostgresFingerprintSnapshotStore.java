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
import java.util.Optional;
import java.util.UUID;

final class PostgresFingerprintSnapshotStore implements ProjectFingerprintSnapshotStore {
    private final PostgresConnectionFactory connections;
    private final PostgresJsonCodec json;

    PostgresFingerprintSnapshotStore(PostgresConnectionFactory connections, PostgresJsonCodec json) {
        this.connections = connections;
        this.json = json;
    }

    @Override
    public ProjectFingerprintSnapshot publish(UUID projectId, String indexSnapshotId, ProjectFingerprint fingerprint)
            throws IOException {
        ProjectFingerprintSnapshot snapshot = new ProjectFingerprintSnapshot(projectId, indexSnapshotId, fingerprint);
        String payload = json.write(snapshot);
        try {
            return connections.inTransaction(connection -> {
                PostgresProjectMutationLock.acquire(connection, projectId);
                Optional<ProjectFingerprintSnapshot> existing = findExisting(connection, projectId, indexSnapshotId);
                if (existing.isPresent()) {
                    ProjectFingerprintSnapshot value = existing.orElseThrow();
                    if (!value.equals(snapshot)) {
                        throw new IOException(
                                "fingerprint snapshot already exists with different content: " + indexSnapshotId);
                    }
                    return value;
                }
                insertSnapshot(connection, projectId, indexSnapshotId, payload);
                return snapshot;
            });
        } catch (SQLException exception) {
            throw new IOException("unable to publish PostgreSQL fingerprint snapshot", exception);
        }
    }

    private Optional<ProjectFingerprintSnapshot> findExisting(
            Connection connection,
            UUID projectId,
            String indexSnapshotId
    ) throws SQLException, IOException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT payload::text FROM fingerprint_snapshots WHERE project_id=? AND snapshot_id=?")) {
            statement.setObject(1, projectId);
            statement.setString(2, indexSnapshotId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(json.read(result.getString(1), ProjectFingerprintSnapshot.class));
            }
        }
    }

    private static void insertSnapshot(
            Connection connection,
            UUID projectId,
            String indexSnapshotId,
            String payload
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO fingerprint_snapshots(project_id,snapshot_id,payload) VALUES (?,?,CAST(? AS jsonb))")) {
            statement.setObject(1, projectId);
            statement.setString(2, indexSnapshotId);
            statement.setString(3, payload);
            statement.executeUpdate();
        }
    }

    @Override
    public void promote(UUID projectId, String indexSnapshotId) throws IOException {
        try {
            connections.inTransaction(connection -> {
                PostgresProjectMutationLock.acquire(connection, projectId);
                if (findExisting(connection, projectId, indexSnapshotId).isEmpty()) {
                    throw new IOException("fingerprint snapshot is not published: " + indexSnapshotId);
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO fingerprint_active(project_id,snapshot_id) VALUES (?,?) "
                                + "ON CONFLICT(project_id) DO UPDATE SET snapshot_id=EXCLUDED.snapshot_id")) {
                    statement.setObject(1, projectId);
                    statement.setString(2, indexSnapshotId);
                    statement.executeUpdate();
                }
                return null;
            });
        } catch (SQLException exception) {
            throw new IOException("unable to promote PostgreSQL fingerprint snapshot", exception);
        }
    }

    @Override
    public Optional<ProjectFingerprintSnapshot> load(UUID projectId, String indexSnapshotId) throws IOException {
        try {
            return connections.withConnection(connection -> findExisting(connection, projectId, indexSnapshotId));
        } catch (SQLException exception) {
            throw new IOException("unable to load PostgreSQL fingerprint snapshot", exception);
        }
    }

    @Override
    public Optional<ProjectFingerprintSnapshot> loadActive(UUID projectId) throws IOException {
        try {
            return connections.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT s.payload::text FROM fingerprint_active a "
                                + "JOIN fingerprint_snapshots s "
                                + "ON s.project_id=a.project_id AND s.snapshot_id=a.snapshot_id "
                                + "WHERE a.project_id=?")) {
                    statement.setObject(1, projectId);
                    try (ResultSet result = statement.executeQuery()) {
                        return result.next()
                                ? Optional.of(json.read(result.getString(1), ProjectFingerprintSnapshot.class))
                                : Optional.empty();
                    }
                }
            });
        } catch (SQLException exception) {
            throw new IOException("unable to load active PostgreSQL fingerprint snapshot", exception);
        }
    }

    @Override
    public List<String> listIndexSnapshotIds(UUID projectId) throws IOException {
        try {
            return connections.withConnection(connection -> {
                List<String> ids = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT snapshot_id FROM fingerprint_snapshots WHERE project_id=? ORDER BY snapshot_id")) {
                    statement.setObject(1, projectId);
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            ids.add(result.getString(1));
                        }
                    }
                }
                return List.copyOf(ids);
            });
        } catch (SQLException exception) {
            throw new IOException("unable to list PostgreSQL fingerprint snapshots", exception);
        }
    }
}
