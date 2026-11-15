package com.minos.storage.local.orchestration;

import com.minos.orchestration.InMemoryIndexStateStore;
import com.minos.orchestration.IndexStateStore;
import com.minos.testsupport.ParkedThreads;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A close attempted from a thread that does not own a project lease is refused and leaves the lease held:
 * each test proves it with a contender on another thread that does not get the lease after the refused
 * close, then gets it once the owner closes.
 */
class ProjectLeaseOwnershipTest {

    /** The in-memory lease waits on its {@code ReentrantLock} inside {@code acquireProjectLease}. */
    private static final String IN_MEMORY_WAIT_FRAME = "com.minos.orchestration.InMemoryIndexStateStore";
    /** The file store lease waits for the JVM lock of its file lease. */
    private static final String FILE_LEASE_WAIT_FRAME = "com.minos.io.BoundedFileLease";
    private static final Duration OBSERVATION_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(100);

    @TempDir
    Path root;

    @Test
    void inMemoryWrongThreadCloseKeepsLeaseHeldUntilOwnerCloses() throws Exception {
        InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        UUID projectId = UUID.randomUUID();
        IndexStateStore.ProjectLease lease = store.acquireProjectLease(projectId);

        Throwable wrongThread = runOnAnotherThread(lease::close);
        assertInstanceOf(IllegalStateException.class, wrongThread);

        assertContenderWaitsUntilOwnerCloses(store, projectId, IN_MEMORY_WAIT_FRAME, () -> {
            lease.close();
            lease.close();
        });
        IndexStateStore.ProjectLease reacquired = assertDoesNotThrow(() -> store.acquireProjectLease(projectId));
        assertContenderWaitsUntilOwnerCloses(store, projectId, IN_MEMORY_WAIT_FRAME, reacquired::close);
    }

    @Test
    void fileStoreWrongThreadCloseKeepsLeaseHeldUntilOwnerCloses() throws Exception {
        FileIndexStateStore store = new FileIndexStateStore(root.resolve("state"));
        UUID projectId = UUID.randomUUID();
        IndexStateStore.ProjectLease lease = store.acquireProjectLease(projectId);

        Throwable wrongThread = runOnAnotherThread(lease::close);
        assertInstanceOf(IllegalStateException.class, wrongThread);

        // The file store lease is reentrant per thread: the contender is always another thread.
        assertContenderWaitsUntilOwnerCloses(store, projectId, FILE_LEASE_WAIT_FRAME, () -> {
            lease.close();
            lease.close();
        });
        IndexStateStore.ProjectLease reacquired = assertDoesNotThrow(() -> store.acquireProjectLease(projectId));
        assertContenderWaitsUntilOwnerCloses(store, projectId, FILE_LEASE_WAIT_FRAME, reacquired::close);
    }

    @Test
    void physicalFileLeaseWrongThreadCloseKeepsLeaseHeldUntilOwnerCloses() throws Exception {
        Path home = root.resolve("home");
        UUID projectId = UUID.randomUUID();
        int retainedBefore = ProjectIndexLease.retainedJvmLockCount();
        ProjectIndexLease lease = ProjectIndexLease.acquire(home, projectId, Duration.ofSeconds(1));

        Throwable wrongThread = runOnAnotherThread(lease::close);
        assertInstanceOf(IllegalStateException.class, wrongThread);

        assertContenderTimesOut(home, projectId);
        lease.close();
        lease.close();
        assertContenderAcquires(home, projectId);

        ProjectIndexLease reacquired = assertDoesNotThrow(() ->
                ProjectIndexLease.acquire(home, projectId, Duration.ofSeconds(1)));
        assertContenderTimesOut(home, projectId);
        reacquired.close();
        assertContenderAcquires(home, projectId);
        assertEquals(retainedBefore, ProjectIndexLease.retainedJvmLockCount(),
                "every JVM lock retained for the project must be released once its leases are closed");
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
                    ProjectIndexLease.acquire(home, projectId, SHORT_TIMEOUT));
            assertTrue(failure.getMessage().contains("timed out"));
        }

        ProjectIndexLease acquired = assertDoesNotThrow(() ->
                ProjectIndexLease.acquire(home, projectId, Duration.ofSeconds(1)));
        assertDoesNotThrow(acquired::close);
    }

    /**
     * Proves the lease is held: a contender on another thread is observed waiting for it and has not got it;
     * once {@code closeOwner} runs, the contender gets the lease, closes it and ends.
     */
    private static void assertContenderWaitsUntilOwnerCloses(
            IndexStateStore store,
            UUID projectId,
            String waitFrame,
            ThrowingAction closeOwner
    ) throws Exception {
        CountDownLatch acquired = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread contender = daemon("project-lease-contender", () -> {
            try (IndexStateStore.ProjectLease ignored = store.acquireProjectLease(projectId)) {
                acquired.countDown();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        contender.start();

        ParkedThreads.awaitParkedIn(contender, waitFrame, () -> acquired.getCount() == 0, OBSERVATION_TIMEOUT);
        assertEquals(1, acquired.getCount(), "a contender must not get a lease its owner still holds");

        closeOwner.run();
        assertTrue(acquired.await(OBSERVATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                () -> "the contender must get the lease once its owner closes it: " + failure.get());
        contender.join(OBSERVATION_TIMEOUT.toMillis());
        assertFalse(contender.isAlive(), "the contender must end after closing its lease");
        assertNull(failure.get());
    }

    /** Proves the file lease is held: another thread asking with a short deadline times out. */
    private static void assertContenderTimesOut(Path home, UUID projectId) throws InterruptedException {
        Throwable refused = runOnAnotherThread(() -> {
            try (ProjectIndexLease ignored = ProjectIndexLease.acquire(home, projectId, SHORT_TIMEOUT)) {
                // Reaching this point means the lease was free: the assertion below reports it.
            }
        });
        IOException timeout = assertInstanceOf(IOException.class, refused,
                "a contender must not get a lease its owner still holds");
        assertTrue(timeout.getMessage().contains("timed out"), timeout.getMessage());
    }

    /** Proves the file lease is free: another thread gets it and closes it. */
    private static void assertContenderAcquires(Path home, UUID projectId) throws InterruptedException {
        Throwable failure = runOnAnotherThread(() -> {
            try (ProjectIndexLease ignored = ProjectIndexLease.acquire(home, projectId, Duration.ofSeconds(1))) {
                // Acquired and released by the same contender thread.
            }
        });
        assertNull(failure, () -> "the contender must get the lease once its owner closes it: " + failure);
    }

    /** Runs {@code action} on a daemon thread joined with a deadline; returns what it threw, or null. */
    private static Throwable runOnAnotherThread(ThrowingAction action) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = daemon("project-lease-other-thread", () -> {
            try {
                action.run();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        thread.start();
        thread.join(OBSERVATION_TIMEOUT.toMillis());
        assertFalse(thread.isAlive(), "the other thread must end within " + OBSERVATION_TIMEOUT);
        return failure.get();
    }

    /** A daemon thread, so a failed assertion never leaves the test JVM waiting for it. */
    private static Thread daemon(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        return thread;
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
