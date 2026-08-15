package com.minos.orchestration;

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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectLeaseOwnershipTest {

    @TempDir
    Path root;

    @Test
    void inMemoryWrongThreadCloseDoesNotPoisonOwnerHandle() throws Exception {
        InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        UUID projectId = UUID.randomUUID();
        IndexStateStore.ProjectLease lease = store.acquireProjectLease(projectId);

        Throwable wrongThread = closeFromAnotherThread(lease::close);
        assertInstanceOf(IllegalStateException.class, wrongThread);

        lease.close();
        try (IndexStateStore.ProjectLease reacquired = store.acquireProjectLease(projectId)) {
            assertTrue(true);
        }
    }

    @Test
    void fileStoreWrongThreadCloseDoesNotPoisonOwnerHandle() throws Exception {
        FileIndexStateStore store = new FileIndexStateStore(root.resolve("state"));
        UUID projectId = UUID.randomUUID();
        IndexStateStore.ProjectLease lease = store.acquireProjectLease(projectId);

        Throwable wrongThread = closeFromAnotherThread(lease::close);
        assertInstanceOf(IllegalStateException.class, wrongThread);

        lease.close();
        try (IndexStateStore.ProjectLease reacquired = store.acquireProjectLease(projectId)) {
            assertTrue(true);
        }
    }

    @Test
    void physicalFileLeaseWrongThreadCloseDoesNotPoisonOwnerHandle() throws Exception {
        UUID projectId = UUID.randomUUID();
        ProjectIndexLease lease = ProjectIndexLease.acquire(root.resolve("home"), projectId, Duration.ofSeconds(1));

        Throwable wrongThread = closeFromAnotherThread(lease::close);
        assertInstanceOf(IllegalStateException.class, wrongThread);

        lease.close();
        try (ProjectIndexLease reacquired = ProjectIndexLease.acquire(
                root.resolve("home"), projectId, Duration.ofSeconds(1))) {
            assertTrue(true);
        }
    }

    @Test
    void physicalFileLeaseTimesOutInsteadOfBlockingIndefinitely() throws Exception {
        Path home = root.resolve("timeout-home");
        UUID projectId = UUID.randomUUID();
        Path directory = home.resolve("locks/indexing");
        Files.createDirectories(directory);
        Path lockFile = directory.resolve(projectId + ".lock");

        try (FileChannel owner = FileChannel.open(
                lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = owner.lock()) {
            IOException failure = assertThrows(IOException.class, () ->
                    ProjectIndexLease.acquire(home, projectId, Duration.ofMillis(100)));
            assertTrue(failure.getMessage().contains("timed out"));
        }

        try (ProjectIndexLease acquired = ProjectIndexLease.acquire(home, projectId, Duration.ofSeconds(1))) {
            assertTrue(true);
        }
    }

    private static Throwable closeFromAnotherThread(ThrowingAction action) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofPlatform().start(() -> {
            try {
                action.run();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        thread.join();
        return failure.get();
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
