package com.minos.storage.local.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotProjectLeaseTest {
    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(100);
    private static final Duration OTHER_THREAD_TIMEOUT = Duration.ofSeconds(5);

    @TempDir Path storage;

    @Test
    void sameProjectSerializesPublicationAndRetentionMutations() throws Exception {
        UUID projectId = UUID.randomUUID();
        try (SnapshotProjectLease first = SnapshotProjectLease.acquire(storage, projectId)) {
            CountDownLatch acquired = new CountDownLatch(1);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var future = executor.submit(() -> {
                    try (SnapshotProjectLease ignored = SnapshotProjectLease.acquire(storage, projectId)) {
                        acquired.countDown();
                    }
                    return null;
                });
                assertFalse(acquired.await(200, TimeUnit.MILLISECONDS));
                first.close();
                assertTrue(acquired.await(5, TimeUnit.SECONDS));
                future.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void wrongThreadCloseKeepsLeaseHeldUntilOwnerCloses() throws Exception {
        Path storageRoot = storage.resolve("owner-thread");
        String projectId = "project-one";
        SnapshotProjectLease lease = SnapshotProjectLease.acquire(storageRoot, projectId, Duration.ofSeconds(1));

        Throwable wrongThread = runOnAnotherThread(lease::close);
        assertInstanceOf(IllegalStateException.class, wrongThread);

        assertContenderTimesOut(storageRoot, projectId);
        lease.close();
        assertContenderAcquires(storageRoot, projectId);

        SnapshotProjectLease reacquired = assertDoesNotThrow(() ->
                SnapshotProjectLease.acquire(storageRoot, projectId, Duration.ofSeconds(1)));
        assertContenderTimesOut(storageRoot, projectId);
        reacquired.close();
        assertContenderAcquires(storageRoot, projectId);
    }

    @Test
    void crossProcessStyleContentionTimesOutInsteadOfBlockingIndefinitely() throws Exception {
        Path storageRoot = storage.resolve("timeout");
        String projectId = "project-two";
        Path lockFile = SnapshotProjectLease.lockFile(storageRoot, projectId);
        Files.createDirectories(lockFile.getParent());

        try (FileChannel owner = FileChannel.open(
                lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = owner.lock()) {
            IOException failure = assertThrows(IOException.class, () ->
                    SnapshotProjectLease.acquire(storageRoot, projectId, SHORT_TIMEOUT));
            assertTrue(failure.getMessage().contains("timed out"));
        }

        SnapshotProjectLease acquired = assertDoesNotThrow(() ->
                SnapshotProjectLease.acquire(storageRoot, projectId, Duration.ofSeconds(1)));
        assertDoesNotThrow(acquired::close);
    }

    /** Proves the lease is held: another thread asking with a short deadline times out. */
    private static void assertContenderTimesOut(Path storageRoot, String projectId) throws InterruptedException {
        Throwable refused = runOnAnotherThread(() -> {
            try (SnapshotProjectLease ignored = SnapshotProjectLease.acquire(storageRoot, projectId, SHORT_TIMEOUT)) {
                // Reaching this point means the lease was free: the assertion below reports it.
            }
        });
        IOException timeout = assertInstanceOf(IOException.class, refused,
                "a contender must not get a lease its owner still holds");
        assertTrue(timeout.getMessage().contains("timed out"), timeout.getMessage());
    }

    /** Proves the lease is free: another thread gets it and closes it. */
    private static void assertContenderAcquires(Path storageRoot, String projectId) throws InterruptedException {
        Throwable failure = runOnAnotherThread(() -> {
            try (SnapshotProjectLease ignored = SnapshotProjectLease.acquire(
                    storageRoot, projectId, Duration.ofSeconds(1))) {
                // Acquired and released by the same contender thread.
            }
        });
        assertNull(failure, () -> "the contender must get the lease once its owner closes it: " + failure);
    }

    /** Runs {@code action} on a daemon thread joined with a deadline; returns what it threw, or null. */
    private static Throwable runOnAnotherThread(ThrowingAction action) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                action.run();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "snapshot-lease-other-thread");
        thread.setDaemon(true);
        thread.start();
        thread.join(OTHER_THREAD_TIMEOUT.toMillis());
        assertFalse(thread.isAlive(), "the other thread must end within " + OTHER_THREAD_TIMEOUT);
        return failure.get();
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
