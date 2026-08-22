package com.minos.storage.postgresql;

import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.ProjectIndexState;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class PostgresIndexStateStore implements IndexStateStore {
    private final PostgresConnectionFactory connections;
    private final PostgresJsonCodec json;

    PostgresIndexStateStore(PostgresConnectionFactory connections, PostgresJsonCodec json) {
        this.connections = connections; this.json = json;
    }

    @Override
    public Optional<ProjectIndexState> findProjectState(UUID projectId) {
        try {
            return connections.withConnection(c -> {
                try (PreparedStatement s = c.prepareStatement(
                        "SELECT availability,active_snapshot_id,latest_run_id,updated_at,detail FROM project_index_state WHERE project_id=?")) {
                    s.setObject(1, projectId);
                    try (ResultSet r = s.executeQuery()) {
                        if (!r.next()) return Optional.empty();
                        return Optional.of(new ProjectIndexState(projectId,
                                ProjectIndexState.Availability.valueOf(r.getString(1)), Optional.ofNullable(r.getString(2)),
                                Optional.ofNullable((UUID) r.getObject(3)), r.getObject(4, OffsetDateTime.class).toInstant(), Optional.ofNullable(r.getString(5))));
                    }
                }
            });
        } catch (SQLException | IOException e) { throw failure("find project state", e); }
    }

    @Override
    public Optional<IndexingRun> findRun(UUID runId) {
        try {
            return connections.withConnection(c -> {
                try (PreparedStatement s = c.prepareStatement("SELECT payload::text FROM indexing_runs WHERE id=?")) {
                    s.setObject(1, runId);
                    try (ResultSet r = s.executeQuery()) {
                        if (!r.next()) return Optional.empty();
                        return Optional.of(json.read(r.getString(1), IndexingRun.class));
                    }
                }
            });
        } catch (SQLException | IOException e) { throw failure("find indexing run", e); }
    }

    @Override
    public List<IndexingRun> listRuns(UUID projectId) {
        try {
            return connections.withConnection(c -> {
                List<IndexingRun> result = new ArrayList<>();
                try (PreparedStatement s = c.prepareStatement(
                        "SELECT payload::text FROM indexing_runs WHERE project_id=? ORDER BY created_at,id")) {
                    s.setObject(1, projectId);
                    try (ResultSet r = s.executeQuery()) {
                        while (r.next()) result.add(json.read(r.getString(1), IndexingRun.class));
                    }
                }
                return List.copyOf(result);
            });
        } catch (SQLException | IOException e) { throw failure("list indexing runs", e); }
    }

    @Override
    public void saveProjectState(ProjectIndexState state) {
        try {
            connections.withConnection(c -> {
                try (PreparedStatement s = c.prepareStatement(
                        "INSERT INTO project_index_state(project_id,availability,active_snapshot_id,latest_run_id,updated_at,detail) VALUES (?,?,?,?,?,?) " +
                                "ON CONFLICT(project_id) DO UPDATE SET availability=EXCLUDED.availability,active_snapshot_id=EXCLUDED.active_snapshot_id,latest_run_id=EXCLUDED.latest_run_id,updated_at=EXCLUDED.updated_at,detail=EXCLUDED.detail")) {
                    s.setObject(1, state.projectId()); s.setString(2, state.availability().name()); s.setString(3, state.activeSnapshotId().orElse(null));
                    s.setObject(4, state.latestRunId().orElse(null)); s.setObject(5, OffsetDateTime.ofInstant(state.updatedAt(), ZoneOffset.UTC));
                    s.setString(6, state.detail().orElse(null)); s.executeUpdate();
                    return null;
                }
            });
        } catch (SQLException | IOException e) { throw failure("save project state", e); }
    }

    @Override
    public void saveRun(IndexingRun run) {
        try {
            connections.withConnection(c -> {
                try (PreparedStatement s = c.prepareStatement(
                        "INSERT INTO indexing_runs(id,project_id,created_at,payload) VALUES (?,?,?,CAST(? AS jsonb)) " +
                                "ON CONFLICT(id) DO UPDATE SET project_id=EXCLUDED.project_id,created_at=EXCLUDED.created_at,payload=EXCLUDED.payload")) {
                    s.setObject(1, run.id()); s.setObject(2, run.projectId()); s.setObject(3, OffsetDateTime.ofInstant(run.createdAt(), ZoneOffset.UTC));
                    s.setString(4, json.write(run)); s.executeUpdate();
                    return null;
                }
            });
        } catch (SQLException | IOException e) { throw failure("save indexing run", e); }
    }

    private static IllegalStateException failure(String action, Exception cause) {
        return new IllegalStateException("PostgreSQL index state store failed to " + action, cause);
    }
}
