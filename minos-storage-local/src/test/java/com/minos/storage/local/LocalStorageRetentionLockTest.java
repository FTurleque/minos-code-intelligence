package com.minos.storage.local;

import com.minos.storage.PersistentRetentionPolicy;
import com.minos.storage.local.incremental.FileProjectFingerprintSnapshotStore;
import com.minos.storage.local.orchestration.FileIndexStateStore;
import com.minos.io.PrivateLocalStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S6 / R9: the per-project retention lock is bounded like every other lock of the repository, is
 * serialised inside the JVM (two compactions of one project never raise
 * {@link java.nio.channels.OverlappingFileLockException}) and is an owner-only file.
 */
class LocalStorageRetentionLockTest {

    private static final PersistentRetentionPolicy POLICY = PersistentRetentionPolicy.DEFAULT;

    private static LocalStorageRetentionService service(Path home, Duration lockTimeout) throws IOException {
        Path knowledgeRoot = home.resolve("symbol-snapshots");
        Path indexRoot = home.resolve("index-state");
        return new LocalStorageRetentionService(
                home, knowledgeRoot, indexRoot,
                new FileProjectFingerprintSnapshotStore(home.resolve("fingerprint-snapshots")),
                new FileIndexStateStore(indexRoot),
                lockTimeout);
    }

    @Test
    void aLockHeldElsewhereFailsClosedAfterTheDeadlineWithoutLeakingAPath(@TempDir Path home) throws Exception {
        UUID projectId = UUID.randomUUID();
        LocalStorageRetentionService retention = service(home, Duration.ofMillis(300));
        Path lockFile = home.resolve("retention-locks").resolve(projectId + ".lock");
        try (FileChannel holder = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = holder.lock()) {
            long started = System.nanoTime();
            IOException failure = assertThrows(IOException.class, () -> retention.compact(projectId, POLICY));
            long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue(failure.getMessage().contains("timed out"), failure.getMessage());
            assertFalse(failure.getMessage().contains(home.toString()), "the message must not carry a path");
            assertTrue(waitedMillis < 5_000, "the wait is bounded by the deadline, not by the holder");
        }
    }

    @Test
    void aCompactionWaitsForTheHolderThenProceedsInsteadOfFailingImmediately(@TempDir Path home) throws Exception {
        UUID projectId = UUID.randomUUID();
        LocalStorageRetentionService retention = service(home, Duration.ofSeconds(10));
        Path lockFile = home.resolve("retention-locks").resolve(projectId + ".lock");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (FileChannel holder = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock held = holder.lock();
            Future<?> compaction = pool.submit(() -> retention.compact(projectId, POLICY));
            Thread.sleep(300);
            assertFalse(compaction.isDone(), "the compaction waits for the lock holder");
            held.release();
            compaction.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twoThreadsCompactingOneProjectAreSerialisedNotRejected(@TempDir Path home) throws Exception {
        UUID projectId = UUID.randomUUID();
        LocalStorageRetentionService retention = service(home, Duration.ofSeconds(10));
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 5; round++) {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Object>> results = new ArrayList<>();
                for (int index = 0; index < threads; index++) {
                    Callable<Object> task = () -> {
                        start.await();
                        return retention.compact(projectId, POLICY);
                    };
                    results.add(pool.submit(task));
                }
                start.countDown();
                for (Future<Object> result : results) {
                    result.get(20, TimeUnit.SECONDS);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theRetentionLockDirectoryAndFileAreOwnerOnly(@TempDir Path home) throws Exception {
        UUID projectId = UUID.randomUUID();
        service(home, Duration.ofSeconds(10)).compact(projectId, POLICY);

        Path directory = home.resolve("retention-locks");
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(directory));
        assertTrue(Files.isRegularFile(directory.resolve(projectId + ".lock")));
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED,
                PrivateLocalStorage.privacyOf(directory.resolve(projectId + ".lock")));
    }

    @Test
    void aSymbolicLinkLockFileIsRefusedNotFollowed(@TempDir Path home) throws Exception {
        UUID projectId = UUID.randomUUID();
        LocalStorageRetentionService retention = service(home, Duration.ofSeconds(10));
        Path outside = Files.writeString(home.resolve("outside.txt"), "outside");
        Files.createSymbolicLink(home.resolve("retention-locks").resolve(projectId + ".lock"), outside);

        assertThrows(IOException.class, () -> retention.compact(projectId, POLICY));
        assertEquals("outside", Files.readString(outside));
    }
}
