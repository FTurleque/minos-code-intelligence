package com.minos.store;

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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotProjectLeaseTest {
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
    void wrongThreadCloseDoesNotPoisonOwnerHandle() throws Exception {
        Path storageRoot = storage.resolve("owner-thread");
        SnapshotProjectLease lease = SnapshotProjectLease.acquire(
                storageRoot, "project-one", Duration.ofSeconds(1));

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofPlatform().start(() -> {
            try {
                lease.close();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        thread.join();
        assertInstanceOf(IllegalStateException.class, failure.get());

        lease.close();
        try (SnapshotProjectLease reacquired = SnapshotProjectLease.acquire(
                storageRoot, "project-one", Duration.ofSeconds(1))) {
            assertTrue(true);
        }
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
                    SnapshotProjectLease.acquire(storageRoot, projectId, Duration.ofMillis(100)));
            assertTrue(failure.getMessage().contains("timed out"));
        }

        try (SnapshotProjectLease acquired = SnapshotProjectLease.acquire(
                storageRoot, projectId, Duration.ofSeconds(1))) {
            assertTrue(true);
        }
    }
}
