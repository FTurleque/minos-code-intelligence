package com.minos.application;

import com.minos.discovery.ProjectDiscoveryService;
import com.minos.domain.Relationship;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolOccurrence;
import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.ProjectIndexState;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.registry.RegisteredWorkspace;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;
import com.minos.store.SnapshotQueryView;
import com.minos.store.SymbolSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What reading the identity of the active snapshot costs the snapshot store: a status read and a repair compare
 * identifiers only, so they never load the snapshot itself; the deprecated contract that returns the snapshot loads
 * it once.
 */
class ActiveSnapshotReadCostTest {

    private static final Instant AT = Instant.parse("2026-10-15T08:00:00Z");

    private UUID projectId;
    private CountingSnapshotStore snapshots;
    private InMemoryIndexStateStore states;
    private ProjectIndexStateReconciler reconciler;

    @BeforeEach
    void wire() {
        projectId = UUID.randomUUID();
        snapshots = new CountingSnapshotStore(new CodeKnowledgeSnapshot(projectId, "snapshot-new",
                List.of(), List.of(), List.of()));
        states = new InMemoryIndexStateStore();
        reconciler = new ProjectIndexStateReconciler(snapshots, states);
    }

    @Test
    void aStatusViewDoesNotLoadTheActiveSnapshot(@TempDir Path home) throws Exception {
        states.saveProjectState(ready("snapshot-new"));
        RegisteredProject project = new RegisteredProject(projectId, home, "status-cost", Optional.empty(), AT, AT);
        ProjectInspectionService inspection = new ProjectInspectionService(home, new UnusedRegistry(), snapshots,
                states, new ProjectDiscoveryService(), List.of());

        ProjectInspectionService.ProjectView view = inspection.statusView(project);

        assertEquals("snapshot-new", view.activeSnapshotId());
        assertEquals(ProjectIndexState.Availability.READY.name(), view.indexState());
        assertEquals(0, snapshots.loads, "a status read must not load the active snapshot");
    }

    @Test
    void theDeprecatedStatusReadLoadsTheSnapshotOnce() throws Exception {
        states.saveProjectState(ready("snapshot-new"));

        ProjectIndexStateReconciler.Reconciliation observed = reconciler.observeStatus(projectId);

        assertEquals(Optional.of("snapshot-new"), observed.activeSnapshot().map(CodeKnowledgeSnapshot::snapshotId));
        assertTrue(snapshots.loads <= 1, "loads: " + snapshots.loads);
    }

    @Test
    void theDeprecatedRepairLoadsTheSnapshotOnce() throws Exception {
        states.saveProjectState(ready("snapshot-old"));

        ProjectIndexStateReconciler.Reconciliation repaired = reconciler.reconcile(projectId);

        assertTrue(repaired.repaired());
        assertEquals(Optional.of("snapshot-new"), repaired.activeSnapshot().map(CodeKnowledgeSnapshot::snapshotId));
        assertEquals(Optional.of("snapshot-new"), states.findProjectState(projectId).orElseThrow().activeSnapshotId());
        assertTrue(snapshots.loads <= 1, "loads: " + snapshots.loads);
    }

    @Test
    void aStateThatReferencesAMissingSnapshotFailsClosedWithoutWriting(@TempDir Path home) {
        snapshots.active = Optional.empty();
        ProjectIndexState persisted = ready("snapshot-old");
        states.saveProjectState(persisted);
        RegisteredProject project = new RegisteredProject(projectId, home, "status-cost", Optional.empty(), AT, AT);
        ProjectInspectionService inspection = new ProjectInspectionService(home, new UnusedRegistry(), snapshots,
                states, new ProjectDiscoveryService(), List.of());

        assertThrows(IOException.class, () -> inspection.statusView(project));
        assertEquals(Optional.of(persisted), states.findProjectState(projectId));
        assertEquals(1, states.writes);
    }

    @Test
    void aConsistentStatusReadComparesIdentifiersOnly() throws Exception {
        states.saveProjectState(ready("snapshot-new"));

        ProjectIndexStateReconciler.ReconciledState observed = reconciler.observeStatusState(projectId);

        assertEquals(Optional.of("snapshot-new"), observed.activeSnapshotId());
        assertFalse(observed.repaired());
        assertEquals(0, snapshots.loads);
        assertEquals(2, snapshots.identityReads, "the state is framed by two reads of the active identity");
    }

    @Test
    void aLaggingStatusReadAnswersTheRepairInMemoryWithoutLoading() throws Exception {
        states.saveProjectState(ready("snapshot-old"));

        ProjectIndexStateReconciler.ReconciledState observed = reconciler.observeStatusState(projectId);

        assertTrue(observed.repaired());
        assertEquals(Optional.of("snapshot-new"), observed.projectState().orElseThrow().activeSnapshotId());
        assertEquals(Optional.of("snapshot-old"), states.findProjectState(projectId).orElseThrow().activeSnapshotId());
        assertEquals(1, states.writes, "a status read writes nothing");
        assertEquals(0, snapshots.loads);
    }

    @Test
    void aRepairComparesIdentifiersOnly() throws Exception {
        states.saveProjectState(ready("snapshot-old"));

        ProjectIndexStateReconciler.ReconciledState repaired = reconciler.reconcileState(projectId);

        assertTrue(repaired.repaired());
        assertEquals(Optional.of("snapshot-new"), repaired.activeSnapshotId());
        assertEquals(Optional.of("snapshot-new"), states.findProjectState(projectId).orElseThrow().activeSnapshotId());
        assertEquals(0, snapshots.loads);
    }

    @Test
    void aPlanReadsTheIdentityOfAProjectWithoutSnapshot() throws Exception {
        snapshots.active = Optional.empty();

        ProjectIndexStateReconciler.ReconciledState planned = reconciler.observeState(projectId);

        assertEquals(Optional.empty(), planned.activeSnapshotId());
        assertEquals(Optional.empty(), planned.projectState());
        assertEquals(0, snapshots.loads);
    }

    @Test
    void theDeprecatedPlanLoadsTheSnapshotOnce() throws Exception {
        states.saveProjectState(ready("snapshot-old"));

        ProjectIndexStateReconciler.Reconciliation planned = reconciler.observe(projectId);

        assertTrue(planned.repaired());
        assertEquals(Optional.of("snapshot-new"), planned.activeSnapshot().map(CodeKnowledgeSnapshot::snapshotId));
        assertEquals(1, snapshots.loads);
    }

    @Test
    void theDeprecatedContractRereadsWhenThePromotionMovesUnderIt() throws Exception {
        states.saveProjectState(ready("snapshot-new"));
        CodeKnowledgeSnapshot promoted = new CodeKnowledgeSnapshot(projectId, "snapshot-next",
                List.of(), List.of(), List.of());
        snapshots.promoteOnLoad = Optional.of(promoted);

        ProjectIndexStateReconciler.Reconciliation observed = reconciler.observeStatus(projectId);

        assertEquals(Optional.of("snapshot-next"), observed.activeSnapshot().map(CodeKnowledgeSnapshot::snapshotId));
        assertTrue(observed.repaired(), "the state still names the previous snapshot, the repair is answered");
        assertEquals(2, snapshots.loads);
    }

    @Test
    void theDeprecatedContractFailsWhenTheActiveSnapshotNeverSettles() {
        states.saveProjectState(ready("snapshot-new"));
        snapshots.unstable = true;

        IOException failure = assertThrows(IOException.class, () -> reconciler.observeStatus(projectId));

        assertTrue(failure.getMessage().contains("changed repeatedly"), failure.getMessage());
    }

    @Test
    void aFailingIdentityReadIsReportedAsAnIoFailure() {
        snapshots.failure = Optional.of(new IllegalStateException("pointer unreadable"));

        IOException failure = assertThrows(IOException.class, () -> reconciler.observeStatusState(projectId));

        assertTrue(failure.getMessage().contains("failed to read active snapshot"), failure.getMessage());
        assertThrows(IOException.class, () -> reconciler.observeStatus(projectId));
    }

    @Test
    void aHostedBindingToTheActiveSnapshotIsAcceptedWithoutLoadingIt() throws Exception {
        MinosApplicationAssembler.requireExactActiveSnapshot(snapshots, projectId, "snapshot-new");

        assertEquals(0, snapshots.loads);
        assertEquals(1, snapshots.identityReads);
    }

    @Test
    void aHostedBindingToAnotherSnapshotOrWithoutSnapshotIsRefused() {
        IOException other = assertThrows(IOException.class,
                () -> MinosApplicationAssembler.requireExactActiveSnapshot(snapshots, projectId, "snapshot-other"));
        assertTrue(other.getMessage().contains("exact active snapshot"), other.getMessage());

        snapshots.active = Optional.empty();
        IOException none = assertThrows(IOException.class,
                () -> MinosApplicationAssembler.requireExactActiveSnapshot(snapshots, projectId, "snapshot-new"));
        assertTrue(none.getMessage().contains("no active snapshot"), none.getMessage());
        assertEquals(0, snapshots.loads);
    }

    private ProjectIndexState ready(String snapshotId) {
        return new ProjectIndexState(projectId, ProjectIndexState.Availability.READY, Optional.of(snapshotId),
                Optional.empty(), AT, Optional.empty());
    }

    static final class CountingSnapshotStore implements CodeKnowledgeSnapshotStore {
        Optional<CodeKnowledgeSnapshot> active;
        /** Promoted by the next full load, before it answers: a promotion between the identity read and the load. */
        Optional<CodeKnowledgeSnapshot> promoteOnLoad = Optional.empty();
        /** Every full load answers a snapshot the identity read has not seen. */
        boolean unstable;
        Optional<RuntimeException> failure = Optional.empty();
        int loads;
        int identityReads;

        CountingSnapshotStore(CodeKnowledgeSnapshot active) {
            this.active = Optional.of(active);
        }

        @Override
        public Optional<CodeKnowledgeSnapshot> loadActiveKnowledge(UUID projectId) {
            loads++;
            failure.ifPresent(cause -> { throw cause; });
            if (unstable) {
                return Optional.of(new CodeKnowledgeSnapshot(projectId, "moving-" + loads,
                        List.of(), List.of(), List.of()));
            }
            if (promoteOnLoad.isPresent()) {
                active = promoteOnLoad;
                promoteOnLoad = Optional.empty();
            }
            return active;
        }

        @Override
        public Optional<String> activeSnapshotId(UUID projectId) {
            identityReads++;
            failure.ifPresent(cause -> { throw cause; });
            return active.map(CodeKnowledgeSnapshot::snapshotId);
        }

        @Override
        public SymbolSnapshot publish(UUID projectId, String snapshotId, Collection<Symbol> symbols) {
            throw new UnsupportedOperationException("publication is not used by this test");
        }

        @Override
        public CodeKnowledgeSnapshot publish(UUID projectId, String snapshotId, Collection<Symbol> symbols,
                                             Collection<SymbolOccurrence> occurrences,
                                             Collection<Relationship> relationships) {
            throw new UnsupportedOperationException("publication is not used by this test");
        }

        @Override
        public Optional<SymbolSnapshot> loadActive(UUID projectId) {
            throw new UnsupportedOperationException("legacy symbol snapshots are not used by this test");
        }

        @Override
        public Optional<SnapshotQueryView> loadActiveQueryView(UUID projectId) {
            throw new UnsupportedOperationException("query views are not used by this test");
        }
    }

    private static final class InMemoryIndexStateStore implements IndexStateStore {
        private final Map<UUID, ProjectIndexState> projects = new HashMap<>();
        private final List<IndexingRun> runs = new ArrayList<>();
        int writes;

        @Override
        public Optional<ProjectIndexState> findProjectState(UUID projectId) {
            return Optional.ofNullable(projects.get(projectId));
        }

        @Override
        public Optional<IndexingRun> findRun(UUID runId) {
            return runs.stream().filter(run -> run.id().equals(runId)).findFirst();
        }

        @Override
        public List<IndexingRun> listRuns(UUID projectId) {
            return runs.stream().filter(run -> run.projectId().equals(projectId)).toList();
        }

        @Override
        public void saveProjectState(ProjectIndexState state) {
            writes++;
            projects.put(state.projectId(), state);
        }

        @Override
        public void saveRun(IndexingRun run) {
            runs.add(run);
        }

        @Override
        public ProjectLease acquireProjectLease(UUID projectId) {
            return () -> { };
        }
    }

    private static final class UnusedRegistry implements ProjectRegistry {
        @Override
        public RegisteredProject registerProject(Path rootPath, String displayName) {
            throw new UnsupportedOperationException("the registry is not used by this test");
        }

        @Override
        public Optional<RegisteredProject> findProject(UUID projectId) {
            return Optional.empty();
        }

        @Override
        public List<RegisteredProject> listProjects() {
            return List.of();
        }

        @Override
        public RegisteredWorkspace createWorkspace(String name) {
            throw new UnsupportedOperationException("the registry is not used by this test");
        }

        @Override
        public RegisteredProject assignProjectToWorkspace(UUID projectId, UUID workspaceId) {
            throw new UnsupportedOperationException("the registry is not used by this test");
        }

        @Override
        public RegisteredProject removeProjectFromWorkspace(UUID projectId) {
            throw new UnsupportedOperationException("the registry is not used by this test");
        }

        @Override
        public Optional<RegisteredWorkspace> findWorkspace(UUID workspaceId) {
            return Optional.empty();
        }

        @Override
        public List<RegisteredWorkspace> listWorkspaces() {
            return List.of();
        }
    }
}
