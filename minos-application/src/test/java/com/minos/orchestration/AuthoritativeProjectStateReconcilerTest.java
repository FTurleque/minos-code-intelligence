package com.minos.orchestration;

import com.minos.orchestration.IndexingRuntimePorts.ActiveSnapshotObservation;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthoritativeProjectStateReconcilerTest {
    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");

    @Test
    void movingSnapshotDuringObservationDoesNotWriteTheObsoleteAuthority() {
        UUID projectId = UUID.randomUUID();
        AtomicReference<String> active = new AtomicReference<>("snapshot-one");
        InMemoryIndexStateStore durable = new InMemoryIndexStateStore();
        durable.saveProjectState(state(projectId, ProjectIndexState.Availability.READY, "snapshot-one"));
        List<String> writes = new ArrayList<>();
        IndexStateStore moving = new DelegatingStore(durable) {
            private boolean moved;

            @Override
            public Optional<ProjectIndexState> findProjectState(UUID id) {
                Optional<ProjectIndexState> state = super.findProjectState(id);
                if (!moved) {
                    moved = true;
                    active.set("snapshot-two");
                }
                return state;
            }

            @Override
            public void saveProjectState(ProjectIndexState state) {
                writes.add(state.activeSnapshotId().orElse("<none>"));
                super.saveProjectState(state);
            }
        };

        ProjectIndexState result = AuthoritativeProjectStateReconciler.reconcile(
                projectId, promoter(active), moving, NOW, "test repair");

        assertEquals(Optional.of("snapshot-two"), result.activeSnapshotId());
        assertEquals(List.of("snapshot-two"), writes);
    }

    @Test
    void snapshotMoveAfterRepairWriteIsReconciledBeforeReturn() {
        UUID projectId = UUID.randomUUID();
        AtomicReference<String> active = new AtomicReference<>("snapshot-one");
        InMemoryIndexStateStore durable = new InMemoryIndexStateStore();
        durable.saveProjectState(state(projectId, ProjectIndexState.Availability.READY, "snapshot-zero"));
        List<String> writes = new ArrayList<>();
        IndexStateStore moving = new DelegatingStore(durable) {
            private boolean moved;

            @Override
            public void saveProjectState(ProjectIndexState state) {
                writes.add(state.activeSnapshotId().orElse("<none>"));
                super.saveProjectState(state);
                if (!moved) {
                    moved = true;
                    active.set("snapshot-two");
                }
            }
        };

        ProjectIndexState result = AuthoritativeProjectStateReconciler.reconcile(
                projectId, promoter(active), moving, NOW, "test repair");

        assertEquals(List.of("snapshot-one", "snapshot-two"), writes);
        assertEquals(Optional.of("snapshot-two"), result.activeSnapshotId());
        assertEquals(Optional.of("snapshot-two"), durable.findProjectState(projectId).orElseThrow().activeSnapshotId());
    }

    @Test
    void refreshingStateThatAlreadyReferencesAuthorityIsPreserved() {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        AtomicReference<String> active = new AtomicReference<>("snapshot-current");
        InMemoryIndexStateStore states = new InMemoryIndexStateStore();
        states.saveProjectState(new ProjectIndexState(
                projectId,
                ProjectIndexState.Availability.REFRESHING,
                Optional.of("snapshot-current"),
                Optional.of(runId),
                NOW,
                Optional.of("run in progress")));

        ProjectIndexState result = AuthoritativeProjectStateReconciler.reconcile(
                projectId, promoter(active), states, NOW, "test repair");

        assertEquals(ProjectIndexState.Availability.REFRESHING, result.availability());
        assertEquals(Optional.of(runId), result.latestRunId());
    }

    @Test
    void supportedNoActiveSnapshotRejectsPersistedReadySnapshot() {
        UUID projectId = UUID.randomUUID();
        InMemoryIndexStateStore states = new InMemoryIndexStateStore();
        states.saveProjectState(state(projectId, ProjectIndexState.Availability.READY, "snapshot-ghost"));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                AuthoritativeProjectStateReconciler.reconcile(
                        projectId, noActivePromoter(), states, NOW, "test repair"));

        assertEquals(Optional.of("snapshot-ghost"), states.findProjectState(projectId).orElseThrow().activeSnapshotId());
        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("snapshot-ghost"));
    }

    @Test
    void supportedNoActiveSnapshotRejectsPersistedRefreshingSnapshot() {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        InMemoryIndexStateStore states = new InMemoryIndexStateStore();
        states.saveProjectState(new ProjectIndexState(
                projectId,
                ProjectIndexState.Availability.REFRESHING,
                Optional.of("snapshot-ghost"),
                Optional.of(runId),
                NOW,
                Optional.of("run in progress")));

        assertThrows(IllegalStateException.class, () ->
                AuthoritativeProjectStateReconciler.reconcile(
                        projectId, noActivePromoter(), states, NOW, "test repair"));
    }

    @Test
    void unsupportedObservationPreservesPersistedState() {
        UUID projectId = UUID.randomUUID();
        InMemoryIndexStateStore states = new InMemoryIndexStateStore();
        ProjectIndexState persisted = state(projectId, ProjectIndexState.Availability.READY, "snapshot-local-only");
        states.saveProjectState(persisted);
        SnapshotPromoter unsupported = (id, runId, stagedSnapshotId) -> { };

        ProjectIndexState result = AuthoritativeProjectStateReconciler.reconcile(
                projectId, unsupported, states, NOW, "test repair");

        assertSame(persisted, result);
    }

    @Test
    void supportedEmptyAuthorityWithoutPersistedSnapshotRemainsConsistent() {
        UUID projectId = UUID.randomUUID();
        InMemoryIndexStateStore states = new InMemoryIndexStateStore();

        ProjectIndexState result = AuthoritativeProjectStateReconciler.reconcile(
                projectId, noActivePromoter(), states, NOW, "test repair");

        assertEquals(ProjectIndexState.Availability.NEVER_INDEXED, result.availability());
        assertEquals(Optional.empty(), result.activeSnapshotId());
    }

    private static SnapshotPromoter promoter(AtomicReference<String> active) {
        return new SnapshotPromoter() {
            @Override
            public void promote(UUID projectId, UUID runId, String stagedSnapshotId) {
                active.set(stagedSnapshotId);
            }

            @Override
            public ActiveSnapshotObservation observeActiveSnapshot(UUID projectId) {
                String snapshotId = active.get();
                return snapshotId == null
                        ? ActiveSnapshotObservation.noActiveSnapshot()
                        : ActiveSnapshotObservation.active(snapshotId);
            }
        };
    }

    private static SnapshotPromoter noActivePromoter() {
        return new SnapshotPromoter() {
            @Override
            public void promote(UUID projectId, UUID runId, String stagedSnapshotId) { }

            @Override
            public ActiveSnapshotObservation observeActiveSnapshot(UUID projectId) {
                return ActiveSnapshotObservation.noActiveSnapshot();
            }
        };
    }

    private static ProjectIndexState state(
            UUID projectId,
            ProjectIndexState.Availability availability,
            String snapshotId
    ) {
        return new ProjectIndexState(projectId, availability, Optional.of(snapshotId), Optional.empty(), NOW, Optional.empty());
    }

    private static class DelegatingStore implements IndexStateStore {
        private final IndexStateStore delegate;

        private DelegatingStore(IndexStateStore delegate) {
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
    }
}
