package com.minos.application;

import com.minos.orchestration.FileIndexStateStore;
import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.ProjectIndexState;
import com.minos.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectIndexStateReconcilerTest {

    @TempDir
    Path tmp;

    @Test
    void repairsCommittedSnapshotMismatchAndRemainsConsistentAfterRestart() throws Exception {
        UUID projectId = UUID.randomUUID();
        Path snapshotRoot = tmp.resolve("snapshots");
        Path stateRoot = tmp.resolve("state");
        FileSymbolSnapshotStore snapshots = new FileSymbolSnapshotStore(snapshotRoot);
        snapshots.publish(projectId, "snapshot-new", List.of(), List.of(), List.of());
        FileIndexStateStore states = new FileIndexStateStore(stateRoot);
        states.saveProjectState(new ProjectIndexState(projectId, ProjectIndexState.Availability.READY,
                Optional.of("snapshot-old"), Optional.empty(), Instant.parse("2026-08-14T08:00:00Z"), Optional.empty()));

        ProjectIndexStateReconciler.Reconciliation repaired =
                new ProjectIndexStateReconciler(snapshots, states).reconcile(projectId);

        assertTrue(repaired.repaired());
        assertEquals(Optional.of("snapshot-new"), repaired.projectState().orElseThrow().activeSnapshotId());
        assertEquals(ProjectIndexState.Availability.READY, repaired.projectState().orElseThrow().availability());

        ProjectIndexStateReconciler.Reconciliation afterRestart = new ProjectIndexStateReconciler(
                new FileSymbolSnapshotStore(snapshotRoot), new FileIndexStateStore(stateRoot)).reconcile(projectId);
        assertFalse(afterRestart.repaired(), "restart should observe the already repaired durable state");
        assertEquals(Optional.of("snapshot-new"), afterRestart.projectState().orElseThrow().activeSnapshotId());
    }

    @Test
    void persistentMetadataFailureIsFailClosedInsteadOfExposingStaleState() throws Exception {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore snapshots = new FileSymbolSnapshotStore(tmp.resolve("snapshots-failing"));
        snapshots.publish(projectId, "snapshot-new", List.of(), List.of(), List.of());
        ProjectIndexState stale = new ProjectIndexState(projectId, ProjectIndexState.Availability.READY,
                Optional.of("snapshot-old"), Optional.empty(), Instant.parse("2026-08-14T08:00:00Z"), Optional.empty());
        IndexStateStore failing = new IndexStateStore() {
            public Optional<ProjectIndexState> findProjectState(UUID id) { return Optional.of(stale); }
            public Optional<IndexingRun> findRun(UUID id) { return Optional.empty(); }
            public List<IndexingRun> listRuns(UUID id) { return List.of(); }
            public void saveProjectState(ProjectIndexState state) { throw new IllegalStateException("synthetic disk failure"); }
            public void saveRun(IndexingRun run) { }
            public ProjectLease acquireProjectLease(UUID id) { return () -> { }; }
        };

        IOException failure = assertThrows(IOException.class,
                () -> new ProjectIndexStateReconciler(snapshots, failing).reconcile(projectId));
        assertTrue(failure.getMessage().contains("reconciliation failed"));
        assertEquals(Optional.of("snapshot-old"), failing.findProjectState(projectId).orElseThrow().activeSnapshotId());
    }

    @Test
    void metadataClaimWithoutAnyActiveSnapshotFailsClosed() throws Exception {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore snapshots = new FileSymbolSnapshotStore(tmp.resolve("snapshots-missing"));
        FileIndexStateStore states = new FileIndexStateStore(tmp.resolve("state-missing"));
        states.saveProjectState(new ProjectIndexState(projectId, ProjectIndexState.Availability.READY,
                Optional.of("ghost"), Optional.empty(), Instant.parse("2026-08-14T08:00:00Z"), Optional.empty()));

        IOException failure = assertThrows(IOException.class,
                () -> new ProjectIndexStateReconciler(snapshots, states).reconcile(projectId));
        assertTrue(failure.getMessage().contains("snapshot store has none"));
    }

    @Test
    void movingActiveSnapshotDuringObservationCannotTriggerAStaleRepair() throws Exception {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore snapshots = new FileSymbolSnapshotStore(tmp.resolve("snapshots-moving-read"));
        snapshots.publish(projectId, "snapshot-one", List.of(), List.of(), List.of());
        FileIndexStateStore durable = new FileIndexStateStore(tmp.resolve("state-moving-read"));
        durable.saveProjectState(state(projectId, ProjectIndexState.Availability.READY, "snapshot-one"));
        List<String> writes = new ArrayList<>();

        IndexStateStore moving = new DelegatingIndexStateStore(durable) {
            private boolean moved;

            @Override
            public Optional<ProjectIndexState> findProjectState(UUID id) {
                Optional<ProjectIndexState> state = super.findProjectState(id);
                if (!moved) {
                    moved = true;
                    publishUnchecked(snapshots, projectId, "snapshot-two");
                }
                return state;
            }

            @Override
            public void saveProjectState(ProjectIndexState state) {
                writes.add(state.activeSnapshotId().orElse("<none>"));
                super.saveProjectState(state);
            }
        };

        ProjectIndexStateReconciler.Reconciliation result =
                new ProjectIndexStateReconciler(snapshots, moving).reconcile(projectId);

        assertTrue(result.repaired());
        assertEquals(Optional.of("snapshot-two"), result.activeSnapshot().map(snapshot -> snapshot.snapshotId()));
        assertEquals(Optional.of("snapshot-two"), result.projectState().orElseThrow().activeSnapshotId());
        assertEquals(List.of("snapshot-two"), writes,
                "the stale snapshot observed before the move must never be written as a repair");
    }

    @Test
    void snapshotMoveAfterRepairWriteIsReobservedBeforeReturning() throws Exception {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore snapshots = new FileSymbolSnapshotStore(tmp.resolve("snapshots-moving-write"));
        snapshots.publish(projectId, "snapshot-one", List.of(), List.of(), List.of());
        FileIndexStateStore durable = new FileIndexStateStore(tmp.resolve("state-moving-write"));
        durable.saveProjectState(state(projectId, ProjectIndexState.Availability.READY, "snapshot-zero"));
        List<String> writes = new ArrayList<>();

        IndexStateStore moving = new DelegatingIndexStateStore(durable) {
            private boolean moved;

            @Override
            public void saveProjectState(ProjectIndexState state) {
                writes.add(state.activeSnapshotId().orElse("<none>"));
                super.saveProjectState(state);
                if (!moved) {
                    moved = true;
                    publishUnchecked(snapshots, projectId, "snapshot-two");
                }
            }
        };

        ProjectIndexStateReconciler.Reconciliation result =
                new ProjectIndexStateReconciler(snapshots, moving).reconcile(projectId);

        assertTrue(result.repaired());
        assertEquals(List.of("snapshot-one", "snapshot-two"), writes);
        assertEquals(Optional.of("snapshot-two"), result.activeSnapshot().map(snapshot -> snapshot.snapshotId()));
        assertEquals(Optional.of("snapshot-two"), result.projectState().orElseThrow().activeSnapshotId());
        assertEquals(Optional.of("snapshot-two"), durable.findProjectState(projectId).orElseThrow().activeSnapshotId());
    }

    @Test
    void refreshingStateForAuthoritativeSnapshotIsPreserved() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        FileSymbolSnapshotStore snapshots = new FileSymbolSnapshotStore(tmp.resolve("snapshots-refreshing"));
        snapshots.publish(projectId, "snapshot-current", List.of(), List.of(), List.of());
        FileIndexStateStore states = new FileIndexStateStore(tmp.resolve("state-refreshing"));
        ProjectIndexState refreshing = new ProjectIndexState(
                projectId,
                ProjectIndexState.Availability.REFRESHING,
                Optional.of("snapshot-current"),
                Optional.of(runId),
                Instant.parse("2026-08-14T08:00:00Z"),
                Optional.of("indexing run in progress"));
        states.saveProjectState(refreshing);

        ProjectIndexStateReconciler.Reconciliation result =
                new ProjectIndexStateReconciler(snapshots, states).reconcile(projectId);

        assertFalse(result.repaired());
        assertEquals(ProjectIndexState.Availability.REFRESHING, result.projectState().orElseThrow().availability());
        assertEquals(Optional.of(runId), result.projectState().orElseThrow().latestRunId());
    }

    private static ProjectIndexState state(
            UUID projectId,
            ProjectIndexState.Availability availability,
            String snapshotId
    ) {
        return new ProjectIndexState(projectId, availability, Optional.of(snapshotId), Optional.empty(),
                Instant.parse("2026-08-14T08:00:00Z"), Optional.empty());
    }

    private static void publishUnchecked(FileSymbolSnapshotStore snapshots, UUID projectId, String snapshotId) {
        try {
            snapshots.publish(projectId, snapshotId, List.of(), List.of(), List.of());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static class DelegatingIndexStateStore implements IndexStateStore {
        private final IndexStateStore delegate;

        private DelegatingIndexStateStore(IndexStateStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<ProjectIndexState> findProjectState(UUID projectId) {
            return delegate.findProjectState(projectId);
        }

        @Override
        public Optional<IndexingRun> findRun(UUID runId) {
            return delegate.findRun(runId);
        }

        @Override
        public List<IndexingRun> listRuns(UUID projectId) {
            return delegate.listRuns(projectId);
        }

        @Override
        public void saveProjectState(ProjectIndexState state) {
            delegate.saveProjectState(state);
        }

        @Override
        public void saveRun(IndexingRun run) {
            delegate.saveRun(run);
        }

        @Override
        public ProjectLease acquireProjectLease(UUID projectId) {
            return delegate.acquireProjectLease(projectId);
        }
    }
}
