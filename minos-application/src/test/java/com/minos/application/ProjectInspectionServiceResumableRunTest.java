package com.minos.application;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.discovery.ProjectDiscoveryService;
import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.IndexingRun.ExecutionCheckpoint;
import com.minos.orchestration.IndexingRun.IndexerExecution;
import com.minos.orchestration.ProjectIndexState;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.registry.RegisteredWorkspace;
import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-ARC-01 : l'état de reprise d'un projet est lu par l'application, sans écriture ni bail du magasin d'état
 * (ADR 0039 §6) ; la CLI, l'API Java et le MCP partagent cette requête.
 */
class ProjectInspectionServiceResumableRunTest {

    private static final Instant CHECKPOINT = Instant.parse("2026-10-23T08:00:00Z");

    private final UUID projectId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();

    @Test
    void theResumableRunIsReadWithoutAnyWriteOrLease(@TempDir Path home) {
        ReadOnlyStateStore states = new ReadOnlyStateStore();
        states.seed(interruptedRun(2), failedState(Optional.of(runId)));

        Optional<ResumableRunStatus> status = service(home, states).resumableRun(projectId.toString());

        assertTrue(status.isPresent());
        ResumableRunStatus run = status.orElseThrow();
        assertEquals(runId, run.runId());
        assertEquals("PROVIDER_EXECUTION", run.phase());
        assertEquals(2, run.resumableTargets());
        assertEquals(3, run.completedExecutions());
        assertEquals(CHECKPOINT.plusSeconds(1), run.checkpointAt());
        assertEquals(59L, run.checkpointAgeSeconds(CHECKPOINT.plusSeconds(60)));
        assertEquals(0L, run.checkpointAgeSeconds(CHECKPOINT.minusSeconds(60)), "an age is never negative");
        assertEquals(List.of(), states.refused, "no write and no lease may be asked to the state store");
    }

    @Test
    void aProjectWithoutResumableRunOffersNothing(@TempDir Path home) {
        ReadOnlyStateStore states = new ReadOnlyStateStore();
        states.seed(interruptedRun(1), failedState(Optional.empty()));

        assertEquals(Optional.empty(), service(home, states).resumableRun(projectId.toString()));
        assertEquals(Optional.empty(), service(home, states).resumableRun(UUID.randomUUID().toString()));
        assertEquals(List.of(), states.refused);
    }

    @Test
    void anIdentifierThatIsNotAUuidOffersNothingInsteadOfFailing(@TempDir Path home) {
        ReadOnlyStateStore states = new ReadOnlyStateStore();

        assertEquals(Optional.empty(), service(home, states).resumableRun("pas-un-uuid"));
        assertThrows(NullPointerException.class, () -> service(home, states).resumableRun(null));
        assertEquals(List.of(), states.refused);
    }

    @Test
    void theStatusRefusesAnInconsistentCount() {
        assertThrows(IllegalArgumentException.class,
                () -> new ResumableRunStatus(runId, "PROVIDER_EXECUTION", CHECKPOINT, 3, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new ResumableRunStatus(runId, "PROVIDER_EXECUTION", CHECKPOINT, -1, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new ResumableRunStatus(runId, " ", CHECKPOINT, 1, 2));
        assertThrows(NullPointerException.class,
                () -> new ResumableRunStatus(null, "PROVIDER_EXECUTION", CHECKPOINT, 1, 2));
        assertThrows(NullPointerException.class,
                () -> new ResumableRunStatus(runId, "PROVIDER_EXECUTION", null, 1, 2));
    }

    private ProjectInspectionService service(Path home, IndexStateStore states) {
        return new ProjectInspectionService(home, new UnusedRegistry(),
                new ActiveSnapshotReadCostTest.CountingSnapshotStore(
                        new CodeKnowledgeSnapshot(projectId, "snapshot", List.of(), List.of(), List.of())),
                states, new ProjectDiscoveryService(), List.of());
    }

    private IndexingRun interruptedRun(int checkpoints) {
        List<IndexerExecution> executions = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            Optional<ExecutionCheckpoint> checkpoint = index < checkpoints
                    ? Optional.of(new ExecutionCheckpoint(Path.of("module-" + index), "1.0", 12L, "a".repeat(64),
                            "b".repeat(64), IndexingMode.FULL, List.of(), CHECKPOINT.plusSeconds(index)))
                    : Optional.empty();
            executions.add(new IndexerExecution(Language.JAVA, "scip-java", Path.of("index-" + index + ".scip"),
                    checkpoint));
        }
        return new IndexingRun(runId, projectId, IndexingRun.Status.INTERRUPTED, IndexingRun.Phase.PROVIDER_EXECUTION,
                CHECKPOINT.minusSeconds(60), Optional.of(CHECKPOINT.minusSeconds(30)), executions, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of("interrupted"), IndexingRun.CURRENT_FORMAT_VERSION);
    }

    private ProjectIndexState failedState(Optional<UUID> resumable) {
        return new ProjectIndexState(projectId, ProjectIndexState.Availability.FAILED, Optional.empty(),
                Optional.of(runId), CHECKPOINT, Optional.of("interrupted"), resumable);
    }

    /** Holds one project state and its runs; every write and every lease is refused and recorded. */
    private static final class ReadOnlyStateStore implements IndexStateStore {
        private final Map<UUID, ProjectIndexState> projects = new HashMap<>();
        private final Map<UUID, IndexingRun> runs = new HashMap<>();
        final List<String> refused = new ArrayList<>();

        void seed(IndexingRun run, ProjectIndexState state) {
            runs.put(run.id(), run);
            projects.put(state.projectId(), state);
        }

        @Override
        public Optional<ProjectIndexState> findProjectState(UUID projectId) {
            return Optional.ofNullable(projects.get(projectId));
        }

        @Override
        public Optional<IndexingRun> findRun(UUID runId) {
            return Optional.ofNullable(runs.get(runId));
        }

        @Override
        public List<IndexingRun> listRuns(UUID projectId) {
            return runs.values().stream().filter(run -> run.projectId().equals(projectId)).toList();
        }

        @Override
        public void saveProjectState(ProjectIndexState state) {
            refused.add("saveProjectState");
            throw new AssertionError("a resume status read must not save a project state");
        }

        @Override
        public void saveRun(IndexingRun run) {
            refused.add("saveRun");
            throw new AssertionError("a resume status read must not save a run");
        }

        @Override
        public ProjectLease acquireProjectLease(UUID projectId) {
            refused.add("acquireProjectLease");
            throw new AssertionError("a resume status read must not take the project lease");
        }
    }

    private static final class UnusedRegistry implements ProjectRegistry {
        @Override public RegisteredProject registerProject(Path rootPath, String displayName) { throw unused(); }
        @Override public Optional<RegisteredProject> findProject(UUID projectId) { return Optional.empty(); }
        @Override public List<RegisteredProject> listProjects() { return List.of(); }
        @Override public RegisteredWorkspace createWorkspace(String name) { throw unused(); }
        @Override public RegisteredProject assignProjectToWorkspace(UUID projectId, UUID workspaceId) { throw unused(); }
        @Override public RegisteredProject removeProjectFromWorkspace(UUID projectId) { throw unused(); }
        @Override public Optional<RegisteredWorkspace> findWorkspace(UUID workspaceId) { return Optional.empty(); }
        @Override public List<RegisteredWorkspace> listWorkspaces() { return List.of(); }

        private static UnsupportedOperationException unused() {
            return new UnsupportedOperationException("the registry is not used by a resume status read");
        }
    }
}
