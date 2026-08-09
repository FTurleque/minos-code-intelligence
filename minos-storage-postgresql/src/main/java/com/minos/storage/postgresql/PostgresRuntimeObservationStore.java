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
            return connections.withConnection(connection -> {
                connection.setAutoCommit(false);
                try {
                    try (PreparedStatement query = connection.prepareStatement(
                            "SELECT source_sha256,payload::text FROM runtime_sessions "
                                    + "WHERE project_id=? AND session_id=?")) {
                        query.setObject(1, projectId);
                        query.setString(2, sessionId);
                        try (ResultSet result = query.executeQuery()) {
                            if (result.next()) {
                                if (!session.sourceSha256().equals(result.getString(1))) {
                                    throw new IOException(
                                            "runtime session is immutable and already exists with different content: "
                                                    + sessionId);
                                }
                                CorrelatedRuntimeSession existing = json.read(
                                        result.getString(2), CorrelatedRuntimeSession.class);
                                connection.rollback();
                                return new SaveResult(existing, true);
                            }
                        }
                    }
                    try (PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO runtime_sessions(project_id,session_id,source_sha256,imported_at,payload) "
                                    + "VALUES (?,?,?,?,CAST(? AS jsonb))")) {
                        statement.setObject(1, projectId);
                        statement.setString(2, sessionId);
                        statement.setString(3, session.sourceSha256());
                        statement.setObject(4, OffsetDateTime.ofInstant(session.importedAt(), ZoneOffset.UTC));
                        statement.setString(5, json.write(session));
                        statement.executeUpdate();
                    }
                    connection.commit();
                    return new SaveResult(session, false);
                } catch (Exception exception) {
                    rollbackPreserving(connection, exception);
                    if (exception instanceof IOException ioException) {
                        throw ioException;
                    }
                    if (exception instanceof SQLException sqlException) {
                        throw sqlException;
                    }
                    throw new IOException("unable to save PostgreSQL runtime session", exception);
                }
            });
        } catch (SQLException exception) {
            throw new IOException("unable to save PostgreSQL runtime session", exception);
        }
    }

    @Override
    public Optional<CorrelatedRuntimeSession> find(UUID projectId, String sessionId) throws IOException {
        try {
            return connections.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT payload::text FROM runtime_sessions WHERE project_id=? AND session_id=?")) {
                    statement.setObject(1, projectId);
                    statement.setString(2, sessionId);
                    try (ResultSet result = statement.executeQuery()) {
                        return result.next()
                                ? Optional.of(json.read(result.getString(1), CorrelatedRuntimeSession.class))
                                : Optional.empty();
                    }
                }
            });
        } catch (SQLException exception) {
            throw new IOException("unable to find PostgreSQL runtime session", exception);
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

    private static void rollbackPreserving(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
