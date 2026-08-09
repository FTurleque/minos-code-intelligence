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
        this.connections = connections; this.json = json;
    }

    @Override
    public SaveResult save(CorrelatedRuntimeSession session) throws IOException {
        UUID projectId = session.session().projectId();
        String sessionId = session.session().sessionId();
        try {
            return connections.withConnection(c -> {
                c.setAutoCommit(false);
                try {
                    try (PreparedStatement q = c.prepareStatement("SELECT source_sha256,payload::text FROM runtime_sessions WHERE project_id=? AND session_id=?")) {
                        q.setObject(1, projectId); q.setString(2, sessionId);
                        try (ResultSet r = q.executeQuery()) {
                            if (r.next()) {
                                if (!session.sourceSha256().equals(r.getString(1))) {
                                    throw new IOException("runtime session is immutable and already exists with different content: " + sessionId);
                                }
                                CorrelatedRuntimeSession existing = json.read(r.getString(2), CorrelatedRuntimeSession.class);
                                c.rollback();
                                return new SaveResult(existing, true);
                            }
                        }
                    }
                    try (PreparedStatement s = c.prepareStatement("INSERT INTO runtime_sessions(project_id,session_id,source_sha256,imported_at,payload) VALUES (?,?,?,?,CAST(? AS jsonb))")) {
                        s.setObject(1, projectId); s.setString(2, sessionId); s.setString(3, session.sourceSha256());
                        s.setObject(4, OffsetDateTime.ofInstant(session.importedAt(), ZoneOffset.UTC)); s.setString(5, json.write(session)); s.executeUpdate();
                    }
                    c.commit();
                    return new SaveResult(session, false);
                } catch (Exception e) {
                    rollbackPreserving(c, e);
                    if (e instanceof IOException io) throw io;
                    if (e instanceof SQLException sql) throw sql;
                    throw new IOException("unable to save PostgreSQL runtime session", e);
                }
            });
        } catch (SQLException e) { throw new IOException("unable to save PostgreSQL runtime session", e); }
    }

    @Override
    public Optional<CorrelatedRuntimeSession> find(UUID projectId, String sessionId) throws IOException {
        try {
            return connections.withConnection(c -> {
                try (PreparedStatement s = c.prepareStatement(
                        "SELECT payload::text FROM runtime_sessions WHERE project_id=? AND session_id=?")) {
                    s.setObject(1, projectId); s.setString(2, sessionId);
                    try (ResultSet r = s.executeQuery()) {
                        return r.next() ? Optional.of(json.read(r.getString(1), CorrelatedRuntimeSession.class)) : Optional.empty();
                    }
                }
            });
        } catch (SQLException e) { throw new IOException("unable to find PostgreSQL runtime session", e); }
    }

    @Override
    public List<CorrelatedRuntimeSession> list(UUID projectId) throws IOException {
        try {
            return connections.withConnection(c -> {
                List<CorrelatedRuntimeSession> result = new ArrayList<>();
                try (PreparedStatement s = c.prepareStatement(
                        "SELECT payload::text FROM runtime_sessions WHERE project_id=? ORDER BY imported_at DESC,session_id")) {
                    s.setObject(1, projectId);
                    try (ResultSet r = s.executeQuery()) {
                        while (r.next()) result.add(json.read(r.getString(1), CorrelatedRuntimeSession.class));
                    }
                }
                return List.copyOf(result);
            });
        } catch (SQLException e) { throw new IOException("unable to list PostgreSQL runtime sessions", e); }
    }

    private static void rollbackPreserving(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
