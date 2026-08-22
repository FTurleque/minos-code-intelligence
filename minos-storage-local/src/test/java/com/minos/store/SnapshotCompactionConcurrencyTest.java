package com.minos.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotCompactionConcurrencyTest {

    @Test
    void compactionDerivesActivePointerOnlyAfterAcquiringProjectLease(@TempDir Path root) throws Exception {
        SnapshotRepository repository = new SnapshotRepository(root);
        ActiveSnapshotRepository activeSnapshots = new ActiveSnapshotRepository(repository);
        UUID projectId = UUID.randomUUID();
        Path directory = repository.ensureProjectDirectory(projectId);
        Path oldSnapshot = Files.writeString(directory.resolve("snapshot-old.knowledge"), "old");
        Path newSnapshot = Files.writeString(directory.resolve("snapshot-new.knowledge"), "new");

        activeSnapshots.promote(projectId, descriptor("old", oldSnapshot));
        SnapshotCompactionService compaction = new SnapshotCompactionService(root);
        AtomicReference<SnapshotRetentionService.RetentionResult> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread worker;
        try (SnapshotProjectLease ignored = SnapshotProjectLease.acquire(root, projectId)) {
            worker = Thread.ofPlatform().name("mne-snapshot-compaction-race").unstarted(() -> {
                try {
                    result.set(compaction.compact(projectId, new SnapshotRetentionPolicy(0)));
                } catch (Throwable exception) {
                    failure.set(exception);
                }
            });
            worker.start();
            awaitBlockedOnLease(worker, Duration.ofSeconds(5));

            // Promotion happens while compaction is waiting for the mutation lease. The pre-MNE
            // implementation had already read the old active pointer at this point and would later
            // delete this newly promoted snapshot as "historical".
            activeSnapshots.promote(projectId, descriptor("new", newSnapshot));
        }

        worker.join(Duration.ofSeconds(10).toMillis());
        assertFalse(worker.isAlive(), "compaction worker must terminate after the project lease is released");
        assertNull(failure.get(), () -> "compaction failed: " + failure.get());
        assertEquals("snapshot-new.knowledge", result.get().activeFileName());
        assertTrue(Files.isRegularFile(newSnapshot), "the newly promoted active snapshot must be protected");
        assertFalse(Files.exists(oldSnapshot), "retention=0 must delete the former active snapshot once historical");
        assertEquals("new", activeSnapshots.read(projectId).orElseThrow().snapshotId());
    }

    private static SnapshotDescriptor descriptor(String id, Path file) {
        return new SnapshotDescriptor(2, id, file.getFileName().toString(), id.substring(0, 1).repeat(64), 1, 0, 0);
    }

    private static void awaitBlockedOnLease(Thread worker, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Thread.State state = worker.getState();
            if (state == Thread.State.WAITING || state == Thread.State.BLOCKED || state == Thread.State.TIMED_WAITING) {
                return;
            }
            if (!worker.isAlive()) return;
            Thread.sleep(5L);
        }
        throw new AssertionError("compaction worker did not block on the project lease within " + timeout);
    }
}
