package com.minos.storage.postgresql;

import com.minos.storage.PersistentRetentionPolicy;
import com.minos.storage.StorageRetentionService;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/** Transactional PostgreSQL retention preserving every active pointer and latest run reference. */
final class PostgresStorageRetentionService implements StorageRetentionService {
    private final PostgresConnectionFactory connections;

    PostgresStorageRetentionService(PostgresConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    @Override
    public RetentionResult compact(UUID projectId, PersistentRetentionPolicy policy) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(policy, "policy");
        try {
            return connections.inTransaction(connection -> {
                PostgresProjectMutationLock.acquire(connection, projectId);
                int knowledge = deleteHistoricalKnowledge(connection, projectId, policy.maxHistoricalSnapshots());
                int fingerprints = deleteHistoricalFingerprints(
                        connection, projectId, policy.maxHistoricalSnapshots());
                int runs = deleteHistoricalRuns(connection, projectId, policy);
                return new RetentionResult(knowledge, fingerprints, runs);
            });
        } catch (SQLException exception) {
            throw new IOException("unable to apply PostgreSQL persistent retention", exception);
        }
    }

    private static int deleteHistoricalKnowledge(
            java.sql.Connection connection,
            UUID projectId,
            int historicalLimit
    ) throws SQLException {
        String sql = """
                WITH ranked AS (
                    SELECT s.snapshot_id,
                           row_number() OVER (ORDER BY s.created_at DESC, s.snapshot_id DESC) AS position
                    FROM knowledge_snapshots s
                    WHERE s.project_id=?
                      AND NOT EXISTS (
                          SELECT 1 FROM knowledge_active a
                          WHERE a.project_id=s.project_id AND a.snapshot_id=s.snapshot_id)
                      AND NOT EXISTS (
                          SELECT 1 FROM project_index_state p
                          WHERE p.project_id=s.project_id AND p.active_snapshot_id=s.snapshot_id)
                )
                DELETE FROM knowledge_snapshots target USING ranked
                WHERE target.project_id=? AND target.snapshot_id=ranked.snapshot_id
                  AND ranked.position>?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, projectId);
            statement.setObject(2, projectId);
            statement.setInt(3, historicalLimit);
            return statement.executeUpdate();
        }
    }

    private static int deleteHistoricalFingerprints(
            java.sql.Connection connection,
            UUID projectId,
            int historicalLimit
    ) throws SQLException {
        String sql = """
                WITH ranked AS (
                    SELECT s.snapshot_id,
                           row_number() OVER (ORDER BY s.created_at DESC, s.snapshot_id DESC) AS position
                    FROM fingerprint_snapshots s
                    WHERE s.project_id=?
                      AND NOT EXISTS (
                          SELECT 1 FROM fingerprint_active a
                          WHERE a.project_id=s.project_id AND a.snapshot_id=s.snapshot_id)
                      AND NOT EXISTS (
                          SELECT 1 FROM knowledge_active a
                          WHERE a.project_id=s.project_id AND a.snapshot_id=s.snapshot_id)
                      AND NOT EXISTS (
                          SELECT 1 FROM project_index_state p
                          WHERE p.project_id=s.project_id AND p.active_snapshot_id=s.snapshot_id)
                )
                DELETE FROM fingerprint_snapshots target USING ranked
                WHERE target.project_id=? AND target.snapshot_id=ranked.snapshot_id
                  AND ranked.position>?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, projectId);
            statement.setObject(2, projectId);
            statement.setInt(3, historicalLimit);
            return statement.executeUpdate();
        }
    }

    private static int deleteHistoricalRuns(
            java.sql.Connection connection,
            UUID projectId,
            PersistentRetentionPolicy policy
    ) throws SQLException {
        String sql = """
                WITH ranked AS (
                    SELECT r.id,
                           (r.payload->>'status' = 'SUCCEEDED') AS succeeded,
                           row_number() OVER (
                               PARTITION BY (r.payload->>'status' = 'SUCCEEDED')
                               ORDER BY r.created_at DESC, r.id DESC) AS position
                    FROM indexing_runs r
                    WHERE r.project_id=?
                )
                DELETE FROM indexing_runs target USING ranked
                WHERE target.project_id=? AND target.id=ranked.id
                  AND NOT EXISTS (
                      SELECT 1 FROM project_index_state p
                      WHERE p.project_id=target.project_id AND p.latest_run_id=target.id)
                  AND ((ranked.succeeded AND ranked.position>?)
                       OR (NOT ranked.succeeded AND ranked.position>?))
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, projectId);
            statement.setObject(2, projectId);
            statement.setInt(3, policy.maxSucceededRuns());
            statement.setInt(4, policy.maxNonSucceededRuns());
            return statement.executeUpdate();
        }
    }
}
