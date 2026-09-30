package com.minos.bootstrap.application;

import com.minos.application.ProjectIndexStateReconciler;
import com.minos.orchestration.ProjectIndexState;
import com.minos.storage.local.orchestration.FileIndexStateStore;
import com.minos.storage.local.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The view a status read guarantees (lot 2, P1): the last state its owner published, possibly behind
 * the writer, never torn, never going back in time.
 *
 * <p>One process publishes a numbered sequence of states while another reads without any lease.
 * Availability is tied to the parity of the sequence number carried in the detail, so a state mixing
 * two publications would be caught; the sequence observed by the reader never decreases.</p>
 */
class ProjectStateReadUnderConcurrentWriteTest {

    private static final int PUBLICATIONS = 200;
    private static final Instant PUBLISHED_AT = Instant.parse("2026-09-30T08:00:00Z");

    @Test
    void aLeaseFreeReaderNeverSeesATornOrRegressingStateWhileAnotherProcessPublishes(@TempDir Path root)
            throws Exception {
        UUID projectId = UUID.randomUUID();
        Path stateRoot = root.resolve("index-state");
        FileIndexStateStore writerProcess = new FileIndexStateStore(stateRoot);
        FileIndexStateStore readerProcess = new FileIndexStateStore(stateRoot);
        FileSymbolSnapshotStore snapshots = new FileSymbolSnapshotStore(root.resolve("symbol-snapshots"));
        snapshots.publish(projectId, "snapshot-1", List.of(), List.of(), List.of());
        writerProcess.saveProjectState(published(projectId, 0));
        ProjectIndexStateReconciler reader = new ProjectIndexStateReconciler(snapshots, readerProcess);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier start = new CyclicBarrier(2);
            AtomicBoolean writerDone = new AtomicBoolean();
            Future<?> writing = pool.submit(() -> {
                start.await();
                try {
                    for (int sequence = 1; sequence <= PUBLICATIONS; sequence++) {
                        writerProcess.saveProjectState(published(projectId, sequence));
                    }
                } finally {
                    writerDone.set(true);
                }
                return null;
            });
            Future<Integer> reading = pool.submit(() -> {
                start.await();
                int last = -1;
                int reads = 0;
                boolean finalRead;
                do {
                    finalRead = writerDone.get();
                    ProjectIndexState state = reader.observeStatus(projectId).projectState().orElseThrow();
                    int sequence = sequenceOf(state);
                    assertEquals(sequence % 2 == 0 ? ProjectIndexState.Availability.READY
                                    : ProjectIndexState.Availability.INDEXING, state.availability(),
                            "state " + sequence + " is one publication, not a mixture of two");
                    assertTrue(sequence >= last, "the read went back in time: " + last + " then " + sequence);
                    last = sequence;
                    reads++;
                } while (!finalRead);
                assertEquals(PUBLICATIONS, last, "after the writer is done the read sees its last publication");
                return reads;
            });

            writing.get();
            assertTrue(reading.get() >= 1);
        } finally {
            pool.shutdownNow();
        }
    }

    private static ProjectIndexState published(UUID projectId, int sequence) {
        boolean ready = sequence % 2 == 0;
        return new ProjectIndexState(
                projectId,
                ready ? ProjectIndexState.Availability.READY : ProjectIndexState.Availability.INDEXING,
                Optional.of("snapshot-1"),
                Optional.empty(),
                PUBLISHED_AT.plusSeconds(sequence),
                Optional.of("publication-" + sequence));
    }

    private static int sequenceOf(ProjectIndexState state) {
        String detail = state.detail().orElseThrow();
        return Integer.parseInt(detail.substring("publication-".length()));
    }
}
