package com.minos.storage.postgresql;

import com.minos.dynamic.CorrelatedRuntimeSession;
import com.minos.dynamic.RuntimeObservationStore;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class PostgresRuntimeObservationStore implements RuntimeObservationStore {
    private final PostgresConnectionFactory connections;
    private final PostgresJsonCodec json;

    PostgresRuntimeObservationStore(PostgresConnectionFactory connections, PostgresJsonCodec json) {
        this.connections = connections;
        this.json = json;
    }

    @Override
    public SaveResult save(CorrelatedRuntimeSession session) throws IOException {
        UUID projectId = session.session().projectId();
        String sessionId = session.session().sessionId();
        try {
            return connections.inTransaction(connection -> {
                Optional<CorrelatedRuntimeSession> existing = findExisting(connection, projectId, sessionId);
                if (existing.isPresent()) {
                    CorrelatedRuntimeSession value = existing.orElseThrow();
                    if (!session.sourceSha256().equals(value.sourceSha256())) {
                        throw new IOException(
                                "runtime session is immutable and already exists with different content: " + sessionId);
                    }
                    return new SaveResult(value, true);
                }
                insertSession(connection, session);
                return new SaveResult(session, false);
            });
        } catch (SQLException exception) {
            throw new IOException("unable to save PostgreSQL runtime session", exception);
        }
    }

    private Optional<CorrelatedRuntimeSession> findExisting(
            Connection connection,
            UUID projectId,
            String sessionId
    ) throws SQLException, IOException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT payload::text FROM runtime_sessions WHERE project_id=? AND session_id=?")) {
            statement.setObject(1, projectId);
            statement.setString(2, sessionId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(json.read(result.getString(1), CorrelatedRuntimeSession.class));
            }
        }
    }

    private void insertSession(Connection connection, CorrelatedRuntimeSession session)
            throws SQLException, IOException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO runtime_sessions(project_id,session_id,source_sha256,imported_at,payload) "
                        + "VALUES (?,?,?,?,CAST(? AS jsonb))")) {
            statement.setObject(1, session.session().projectId());
            statement.setString(2, session.session().sessionId());
            statement.setString(3, session.sourceSha256());
            statement.setObject(4, OffsetDateTime.ofInstant(session.importedAt(), ZoneOffset.UTC));
            statement.setString(5, json.write(session));
            statement.executeUpdate();
        }
    }

    @Override
    public Optional<CorrelatedRuntimeSession> find(UUID projectId, String sessionId) throws IOException {
        try {
            return connections.withConnection(connection -> findExisting(connection, projectId, sessionId));
        } catch (SQLException exception) {
            throw new IOException("unable to find PostgreSQL runtime session", exception);
        }
    }

    @Override
    public List<CorrelatedRuntimeSession> list(UUID projectId, String snapshotId, int limit) throws IOException {
        if (limit < 1) throw new IllegalArgumentException("runtime session limit must be positive");
        try {
            return connections.withConnection(connection -> {
                List<CorrelatedRuntimeSession> sessions = new ArrayList<>();
                String sql = snapshotId == null
                        ? "SELECT payload::text FROM runtime_sessions WHERE project_id=? ORDER BY imported_at DESC,session_id LIMIT ?"
                        : "SELECT payload::text FROM runtime_sessions WHERE project_id=? "
                            + "AND payload->'session'->>'snapshotId'=? ORDER BY imported_at DESC,session_id LIMIT ?";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setObject(1, projectId);
                    int limitIndex;
                    if (snapshotId == null) {
                        limitIndex = 2;
                    } else {
                        statement.setString(2, snapshotId);
                        limitIndex = 3;
                    }
                    statement.setInt(limitIndex, limit);
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) sessions.add(json.read(result.getString(1), CorrelatedRuntimeSession.class));
                    }
                }
                return List.copyOf(sessions);
            });
        } catch (SQLException exception) {
            throw new IOException("unable to list bounded PostgreSQL runtime sessions", exception);
        }
    }

    @Override
    public List<CorrelatedRuntimeSession> list(UUID projectId) throws IOException {
        try {
            return connections.withConnection(connection -> {
                List<CorrelatedRuntimeSession> sessions = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT payload::text FROM runtime_sessions "
                                + "WHERE project_id=? ORDER BY imported_at DESC,session_id")) {
                    statement.setObject(1, projectId);
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            sessions.add(json.read(result.getString(1), CorrelatedRuntimeSession.class));
                        }
                    }
                }
                return List.copyOf(sessions);
            });
        } catch (SQLException exception) {
            throw new IOException("unable to list PostgreSQL runtime sessions", exception);
        }
    }
}
