package com.minos.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedFileLeaseTest {

    @TempDir
    Path root;

    @Test
    void ownerCloseIsIdempotentAndLeaseCanBeReacquired() throws Exception {
        Path lockFile = root.resolve("lease.lock");
        ReentrantLock jvmLock = new ReentrantLock();

        BoundedFileLease lease = BoundedFileLease.acquire(
                lockFile, jvmLock, Duration.ofSeconds(1), "test lease");
        lease.close();
        lease.close();

        try (BoundedFileLease reacquired = BoundedFileLease.acquire(
                lockFile, jvmLock, Duration.ofSeconds(1), "test lease")) {
            assertTrue(jvmLock.isHeldByCurrentThread());
        }
        assertFalse(jvmLock.isLocked());
    }

    @Test
    void wrongThreadCloseDoesNotPoisonOwnerHandle() throws Exception {
        Path lockFile = root.resolve("owner.lock");
        ReentrantLock jvmLock = new ReentrantLock();
        BoundedFileLease lease = BoundedFileLease.acquire(
                lockFile, jvmLock, Duration.ofSeconds(1), "owner lease");

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
        assertTrue(jvmLock.isHeldByCurrentThread());
        lease.close();
        assertFalse(jvmLock.isLocked());
    }

    @Test
    void fileContentionTimesOutAndReleasesJvmLock() throws Exception {
        Path lockFile = root.resolve("file-contention.lock");
        Files.createFile(lockFile);
        ReentrantLock jvmLock = new ReentrantLock();

        try (FileChannel owner = FileChannel.open(lockFile, StandardOpenOption.WRITE);
             FileLock ignored = owner.lock()) {
            IOException failure = assertThrows(IOException.class, () -> BoundedFileLease.acquire(
                    lockFile, jvmLock, Duration.ofMillis(100), "contended file lease"));
            assertTrue(failure.getMessage().contains("timed out"));
            assertFalse(jvmLock.isLocked());
        }

        try (BoundedFileLease ignored = BoundedFileLease.acquire(
                lockFile, jvmLock, Duration.ofSeconds(1), "contended file lease")) {
            assertTrue(jvmLock.isHeldByCurrentThread());
        }
    }

    @Test
    void jvmContentionTimesOutBeforeOpeningFileLease() throws Exception {
        Path lockFile = root.resolve("jvm-contention.lock");
        ReentrantLock jvmLock = new ReentrantLock();
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofPlatform().start(() -> {
            jvmLock.lock();
            try {
                acquired.countDown();
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                jvmLock.unlock();
            }
        });
        assertTrue(acquired.await(1, TimeUnit.SECONDS));

        try {
            IOException failure = assertThrows(IOException.class, () -> BoundedFileLease.acquire(
                    lockFile, jvmLock, Duration.ofMillis(100), "contended JVM lease"));
            assertTrue(failure.getMessage().contains("timed out"));
            assertFalse(Files.exists(lockFile));
        } finally {
            release.countDown();
            holder.join();
        }
    }
}
