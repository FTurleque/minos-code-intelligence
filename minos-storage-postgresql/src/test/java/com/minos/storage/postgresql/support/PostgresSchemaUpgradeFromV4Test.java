package com.minos.storage.postgresql.support;

import com.minos.orchestration.ProjectIndexState;
import com.minos.storage.postgresql.PostgresTestSupport;
import com.minos.storage.postgresql.project.PostgresIndexStateStore;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ADR 0039 §2: a database at schema v4, whose project states have no resume column, migrates to v5; the existing
 * state is read back without a resume offer, and a new offer is then kept.
 */
class PostgresSchemaUpgradeFromV4Test extends PostgresTestSupport {

    @Test
    void aPopulatedV4DatabaseGainsTheResumeColumnAndKeepsItsStates() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID latestRunId = UUID.randomUUID();
        PostgresIndexStateStore store = new PostgresIndexStateStore(connections, new PostgresJsonCodec());
        store.saveProjectState(new ProjectIndexState(projectId, ProjectIndexState.Availability.FAILED,
                Optional.empty(), Optional.of(latestRunId), Instant.parse("2026-10-01T00:00:00Z"),
                Optional.of("failed")));
        downgradeToV4();

        new PostgresSchemaMigrator(connections).migrate();

        assertEquals(List.of(1, 2, 3, 4, 5), versions());
        ProjectIndexState migrated = store.findProjectState(projectId).orElseThrow();
        assertEquals(Optional.of(latestRunId), migrated.latestRunId());
        assertEquals(Optional.empty(), migrated.resumableRunId());

        UUID interrupted = UUID.randomUUID();
        store.saveProjectState(new ProjectIndexState(projectId, ProjectIndexState.Availability.FAILED,
                Optional.empty(), Optional.of(interrupted), Instant.parse("2026-10-02T00:00:00Z"),
                Optional.of("interrupted"), Optional.of(interrupted)));
        assertEquals(Optional.of(interrupted),
                store.findProjectState(projectId).orElseThrow().resumableRunId());
    }

    private void downgradeToV4() throws Exception {
        connections.inTransaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE project_index_state DROP COLUMN resumable_run_id");
                statement.execute("DELETE FROM schema_version WHERE version = 5");
            }
            return null;
        });
    }

    private List<Integer> versions() throws Exception {
        return connections.withConnection(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT version FROM schema_version ORDER BY version")) {
                List<Integer> versions = new ArrayList<>();
                while (result.next()) versions.add(result.getInt(1));
                return versions;
            }
        });
    }
}
