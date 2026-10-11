package com.minos.storage.postgresql.project;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.ProjectIndexState;
import com.minos.storage.postgresql.PostgresTestSupport;
import com.minos.storage.postgresql.support.PostgresConnectionFactory;
import com.minos.storage.postgresql.support.PostgresJsonCodec;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PostgresIndexStateStoreTest extends PostgresTestSupport {
    private static final Instant CREATED_AT = Instant.parse("2026-08-15T12:00:00Z");

    @Test
    void aProjectStateIsStoredWithEveryOptionalFieldAndReadBackUnchanged() {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        PostgresIndexStateStore store = store();
        ProjectIndexState complete = new ProjectIndexState(projectId, ProjectIndexState.Availability.STALE,
                Optional.of("snapshot-1"), Optional.of(runId), CREATED_AT, Optional.of("refresh failed"));

        store.saveProjectState(complete);

        assertEquals(Optional.of(complete), store().findProjectState(projectId));
        assertEquals(Optional.empty(), store.findProjectState(UUID.randomUUID()));
    }

    @Test
    void savingAProjectStateAgainReplacesItAndClearsTheFieldsThatBecameAbsent() {
        UUID projectId = UUID.randomUUID();
        PostgresIndexStateStore store = store();
        store.saveProjectState(new ProjectIndexState(projectId, ProjectIndexState.Availability.READY,
                Optional.of("snapshot-1"), Optional.of(UUID.randomUUID()), CREATED_AT, Optional.of("ready")));
        ProjectIndexState bare = ProjectIndexState.neverIndexed(projectId, CREATED_AT.plusSeconds(60));

        store.saveProjectState(bare);

        ProjectIndexState stored = store.findProjectState(projectId).orElseThrow();
        assertEquals(bare, stored);
        assertEquals(Optional.empty(), stored.activeSnapshotId());
        assertEquals(Optional.empty(), stored.latestRunId());
        assertEquals(Optional.empty(), stored.detail());
    }

    @Test
    void aRunIsStoredWithItsExecutionsAndReadBackUnchanged() {
        UUID projectId = UUID.randomUUID();
        PostgresIndexStateStore store = store();
        IndexingRun run = succeeded(UUID.randomUUID(), projectId, CREATED_AT);

        store.saveRun(run);

        IndexingRun stored = store().findRun(run.id()).orElseThrow();
        assertEquals(run, stored);
        assertEquals(Path.of("artifacts", "java", "index.scip"), stored.executions().getFirst().finalArtifact());
        assertEquals(Optional.empty(), store.findRun(UUID.randomUUID()));
    }

    @Test
    void savingARunAgainReplacesItsPayload() {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        PostgresIndexStateStore store = store();
        store.saveRun(running(runId, projectId, CREATED_AT));
        IndexingRun finished = succeeded(runId, projectId, CREATED_AT);

        store.saveRun(finished);

        assertEquals(Optional.of(finished), store.findRun(runId));
        assertEquals(List.of(finished), store.listRuns(projectId));
    }

    @Test
    void runsAreListedPerProjectInCreationOrder() {
        UUID projectId = UUID.randomUUID();
        UUID otherProject = UUID.randomUUID();
        PostgresIndexStateStore store = store();
        IndexingRun newest = running(UUID.randomUUID(), projectId, CREATED_AT.plusSeconds(120));
        IndexingRun oldest = succeeded(UUID.randomUUID(), projectId, CREATED_AT);
        IndexingRun middle = succeeded(UUID.randomUUID(), projectId, CREATED_AT.plusSeconds(60));
        IndexingRun foreign = succeeded(UUID.randomUUID(), otherProject, CREATED_AT.plusSeconds(30));
        for (IndexingRun run : List.of(newest, oldest, middle, foreign)) store.saveRun(run);

        assertEquals(List.of(oldest, middle, newest), store.listRuns(projectId));
        assertEquals(List.of(foreign), store.listRuns(otherProject));
        assertEquals(List.of(), store.listRuns(UUID.randomUUID()));
    }

    @Test
    void anAvailabilityWrittenByAnotherVersionIsRefusedInsteadOfBeingGuessed() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresIndexStateStore store = store();
        store.saveProjectState(ProjectIndexState.neverIndexed(projectId, CREATED_AT));
        connections.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE project_index_state SET availability='FUTURE_STATE' WHERE project_id=?")) {
                statement.setObject(1, projectId);
                return statement.executeUpdate();
            }
        });

        assertThrows(IllegalArgumentException.class, () -> store.findProjectState(projectId));
    }

    @Test
    void aStorageFailureIsReportedWithTheOperationAndItsCause() throws Exception {
        PostgresConnectionFactory closed = createFactory("minos");
        closed.close();
        PostgresIndexStateStore store = new PostgresIndexStateStore(closed, new PostgresJsonCodec());
        UUID projectId = UUID.randomUUID();
        ProjectIndexState state = ProjectIndexState.neverIndexed(projectId, CREATED_AT);
        IndexingRun run = running(UUID.randomUUID(), projectId, CREATED_AT);

        IllegalStateException findState = assertThrows(IllegalStateException.class, () -> store.findProjectState(projectId));
        IllegalStateException findRun = assertThrows(IllegalStateException.class, () -> store.findRun(projectId));
        IllegalStateException listRuns = assertThrows(IllegalStateException.class, () -> store.listRuns(projectId));
        IllegalStateException saveState = assertThrows(IllegalStateException.class, () -> store.saveProjectState(state));
        IllegalStateException saveRun = assertThrows(IllegalStateException.class, () -> store.saveRun(run));

        assertEquals("PostgreSQL index state store failed to find project state", findState.getMessage());
        assertEquals("PostgreSQL index state store failed to find indexing run", findRun.getMessage());
        assertEquals("PostgreSQL index state store failed to list indexing runs", listRuns.getMessage());
        assertEquals("PostgreSQL index state store failed to save project state", saveState.getMessage());
        assertEquals("PostgreSQL index state store failed to save indexing run", saveRun.getMessage());
        for (IllegalStateException failure : List.of(findState, findRun, listRuns, saveState, saveRun)) {
            assertInstanceOf(SQLException.class, failure.getCause());
        }
        assertEquals(Optional.empty(), store().findProjectState(projectId), "nothing may be written through a closed pool");
        assertEquals(List.of(), store().listRuns(projectId));
    }

    @Test
    void aRunPayloadThatCannotBeDecodedFailsTheReadInsteadOfBeingSkipped() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        PostgresIndexStateStore store = store();
        store.saveRun(running(runId, projectId, CREATED_AT));
        connections.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE indexing_runs SET payload=CAST('{\"status\":\"UNKNOWN_STATUS\"}' AS jsonb) WHERE id=?")) {
                statement.setObject(1, runId);
                return statement.executeUpdate();
            }
        });

        IllegalStateException find = assertThrows(IllegalStateException.class, () -> store.findRun(runId));
        IllegalStateException list = assertThrows(IllegalStateException.class, () -> store.listRuns(projectId));

        assertEquals("PostgreSQL index state store failed to find indexing run", find.getMessage());
        assertEquals("PostgreSQL index state store failed to list indexing runs", list.getMessage());
    }

    private PostgresIndexStateStore store() {
        return new PostgresIndexStateStore(connections, new PostgresJsonCodec());
    }

    private static IndexingRun running(UUID runId, UUID projectId, Instant createdAt) {
        return new IndexingRun(runId, projectId, IndexingRun.Status.RUNNING, IndexingRun.Phase.PROVIDER_EXECUTION,
                createdAt, Optional.empty(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), IndexingRun.CURRENT_FORMAT_VERSION);
    }

    private static IndexingRun succeeded(UUID runId, UUID projectId, Instant createdAt) {
        IndexingRun.IndexerExecution execution = new IndexingRun.IndexerExecution(
                Language.JAVA, "scip-java", Path.of("artifacts", "java", "index.scip"));
        return new IndexingRun(runId, projectId, IndexingRun.Status.SUCCEEDED, IndexingRun.Phase.COMPLETED,
                createdAt, Optional.of(createdAt.plusSeconds(5)), List.of(execution), Optional.of("staged-1"),
                Optional.empty(), Optional.of("snapshot-1"), Optional.of("indexed"), IndexingRun.CURRENT_FORMAT_VERSION);
    }
}
