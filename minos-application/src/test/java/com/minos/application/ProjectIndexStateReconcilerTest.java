package com.minos.application;

import com.minos.orchestration.FileIndexStateStore;
import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.ProjectIndexState;
import com.minos.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
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
}
