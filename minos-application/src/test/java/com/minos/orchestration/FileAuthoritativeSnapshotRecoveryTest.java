package com.minos.orchestration;

import com.minos.orchestration.IndexingRuntimePorts.ActiveSnapshotObservation;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileAuthoritativeSnapshotRecoveryTest {

    @Test
    void restartDoesNotExposePersistedReadyStateWhenAuthoritativeStoreIsEmpty(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        Path stateRoot = root.resolve("state");
        FileIndexStateStore firstProcess = new FileIndexStateStore(stateRoot);
        firstProcess.saveProjectState(new ProjectIndexState(
                projectId,
                ProjectIndexState.Availability.READY,
                Optional.of("snapshot-ghost"),
                Optional.empty(),
                Instant.parse("2026-08-14T08:00:00Z"),
                Optional.of("persisted before restart")));

        AtomicReference<ActiveSnapshotObservation> authority =
                new AtomicReference<>(ActiveSnapshotObservation.noActiveSnapshot());
        SnapshotPromoter promoter = promoter(authority);
        FileIndexStateStore reopenedState = new FileIndexStateStore(stateRoot);
        IndexingLifecycleService restarted = service(reopenedState, promoter);

        assertThrows(IllegalStateException.class, () -> restarted.projectState(projectId));
        assertEquals(Optional.of("snapshot-ghost"),
                reopenedState.findProjectState(projectId).orElseThrow().activeSnapshotId(),
                "fail-closed read must not silently rewrite a ghost as valid metadata");

        authority.set(ActiveSnapshotObservation.active("snapshot-recovered"));
        ProjectIndexState recovered = restarted.projectState(projectId);
        assertEquals(ProjectIndexState.Availability.READY, recovered.availability());
        assertEquals(Optional.of("snapshot-recovered"), recovered.activeSnapshotId());

        FileIndexStateStore secondRestart = new FileIndexStateStore(stateRoot);
        assertEquals(Optional.of("snapshot-recovered"),
                secondRestart.findProjectState(projectId).orElseThrow().activeSnapshotId(),
                "reconciliation must be durable across a subsequent restart");
    }

    private static IndexingLifecycleService service(IndexStateStore states, SnapshotPromoter promoter) {
        return new IndexingLifecycleService(
                List.of(),
                request -> { throw new AssertionError("staging is not part of projectState recovery"); },
                promoter,
                states);
    }

    private static SnapshotPromoter promoter(AtomicReference<ActiveSnapshotObservation> authority) {
        return new SnapshotPromoter() {
            @Override
            public void promote(UUID projectId, UUID runId, String stagedSnapshotId) { }

            @Override
            public ActiveSnapshotObservation observeActiveSnapshot(UUID projectId) {
                return authority.get();
            }
        };
    }
}
