package com.minos.storage.postgresql.project;

import com.minos.storage.postgresql.PostgresTestSupport;
import com.minos.storage.postgresql.support.PostgresConnectionFactory;
import com.minos.storage.postgresql.support.PostgresProjectMutationLock;
import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.ProjectIndexState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ProjectMutationIndexStateStoreTest extends PostgresTestSupport {
    private static final Duration WAIT_DEADLINE = Duration.ofSeconds(10);

    @Test
    void sameProjectMutationWaitsForSharedLock() throws Exception {
        UUID id = UUID.randomUUID();
        RecordingStore delegate = new RecordingStore();
        ProjectMutationIndexStateStore store = new ProjectMutationIndexStateStore(connections, delegate);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch writerStarted = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var holder = pool.submit(() -> {
                try {
                    connections.inTransaction(c -> {
                        PostgresProjectMutationLock.acquire(c, id);
                        held.countDown();
                        try {
                            release.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return null;
                    });
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            assertTrue(held.await(10, TimeUnit.SECONDS));
            ProjectIndexState state = ready(id);
            var writer = pool.submit(() -> {
                writerStarted.countDown();
                store.saveProjectState(state);
            });
            assertTrue(writerStarted.await(10, TimeUnit.SECONDS));
            // pg_locks shows the writer's advisory lock request as not granted: the wait is observed, not assumed.
            awaitAdvisoryLockWaiter(id, WAIT_DEADLINE);
            assertFalse(writer.isDone(), "same-project state mutation must remain blocked by the shared advisory lock");
            assertNull(delegate.state.get());
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            writer.get(10, TimeUnit.SECONDS);
            assertTrue(delegate.state.get() == state);
        }
    }

    @Test
    void lifecycleLeaseIsReentrantOnSameThread() {
        UUID id = UUID.randomUUID();
        RecordingStore delegate = new RecordingStore();
        ProjectMutationIndexStateStore store = new ProjectMutationIndexStateStore(connections, delegate);
        ProjectIndexState state = ready(id);

        try (IndexStateStore.ProjectLease outer = store.acquireProjectLease(id)) {
            try (IndexStateStore.ProjectLease nested = store.acquireProjectLease(id)) {
                store.saveProjectState(state);
            }
            assertTrue(delegate.state.get() == state);
        }

        try (IndexStateStore.ProjectLease reacquired = store.acquireProjectLease(id)) {
            assertTrue(delegate.state.get() == state);
        }
    }

    @Test
    void lifecycleLeaseBlocksSameProjectMutationAcrossConnections() throws Exception {
        UUID id = UUID.randomUUID();
        RecordingStore delegate = new RecordingStore();
        ProjectMutationIndexStateStore store = new ProjectMutationIndexStateStore(connections, delegate);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch writerStarted = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(2)) {
            var holder = pool.submit(() -> {
                try (IndexStateStore.ProjectLease ignored = store.acquireProjectLease(id)) {
                    held.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test timed out while holding lifecycle lease");
                    }
                }
                return null;
            });
            assertTrue(held.await(10, TimeUnit.SECONDS));
            ProjectIndexState state = ready(id);
            var writer = pool.submit(() -> {
                writerStarted.countDown();
                store.saveProjectState(state);
            });
            assertTrue(writerStarted.await(10, TimeUnit.SECONDS));
            // pg_locks shows the writer's transaction lock request as not granted behind the session lease.
            awaitAdvisoryLockWaiter(id, WAIT_DEADLINE);
            assertFalse(writer.isDone(), "project mutation must wait for the session lifecycle lease");
            assertNull(delegate.state.get());

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            writer.get(10, TimeUnit.SECONDS);
            assertTrue(delegate.state.get() == state);
        }
    }

    @Test
    void sameProjectLifecycleWaitersDoNotConsumeQueryPool() throws Exception {
        UUID id = UUID.randomUUID();
        ProjectMutationIndexStateStore store = new ProjectMutationIndexStateStore(connections, new RecordingStore());
        CountDownLatch firstHeld = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch waitersStarted = new CountDownLatch(6);

        try (var pool = Executors.newFixedThreadPool(8)) {
            var first = pool.submit(() -> {
                try (IndexStateStore.ProjectLease ignored = store.acquireProjectLease(id)) {
                    firstHeld.countDown();
                    if (!releaseFirst.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test timed out while holding lifecycle lease");
                    }
                }
                return null;
            });
            assertTrue(firstHeld.await(10, TimeUnit.SECONDS));

            List<Future<?>> waiters = new ArrayList<>();
            List<Thread> waiterThreads = Collections.synchronizedList(new ArrayList<>());
            for (int index = 0; index < 6; index++) {
                waiters.add(pool.submit(() -> {
                    waiterThreads.add(Thread.currentThread());
                    waitersStarted.countDown();
                    try (IndexStateStore.ProjectLease ignored = store.acquireProjectLease(id)) {
                        return null;
                    }
                }));
            }
            assertTrue(waitersStarted.await(10, TimeUnit.SECONDS));
            // Every waiter is observed queued on the local project gate (a lock of this JVM) before the pool
            // is measured: the measurement no longer depends on the waiters having had time to arrive.
            for (int index = 0; index < waiters.size(); index++) {
                awaitParkedInStore(waiterThreads.get(index), waiters, WAIT_DEADLINE);
            }

            PostgresConnectionFactory.PoolStats duringWait = connections.poolStats();
            assertEquals(0, duringWait.leased(),
                    "lifecycle holder and same-project waiters must not reserve ordinary query connections");
            assertEquals(1, duringWait.dedicated(),
                    "the local project gate must allow only the current lifecycle owner to reserve a dedicated session");

            int one = connections.withConnection(connection -> {
                try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT 1")) {
                    assertTrue(result.next());
                    return result.getInt(1);
                }
            });
            assertEquals(1, one, "unrelated query traffic must remain serviceable while lifecycle waiters queue");

            releaseFirst.countDown();
            first.get(10, TimeUnit.SECONDS);
            for (var waiter : waiters) waiter.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void secondLifecycleLeaseWaitsUntilFirstIsReleased() throws Exception {
        UUID id = UUID.randomUUID();
        ProjectMutationIndexStateStore store = new ProjectMutationIndexStateStore(connections, new RecordingStore());
        CountDownLatch firstHeld = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondAcquired = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                try (IndexStateStore.ProjectLease ignored = store.acquireProjectLease(id)) {
                    firstHeld.countDown();
                    if (!releaseFirst.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test timed out while holding first lifecycle lease");
                    }
                }
                return null;
            });
            assertTrue(firstHeld.await(10, TimeUnit.SECONDS));
            var second = pool.submit(() -> {
                secondStarted.countDown();
                try (IndexStateStore.ProjectLease ignored = store.acquireProjectLease(id)) {
                    secondAcquired.countDown();
                }
                return null;
            });
            assertTrue(secondStarted.await(10, TimeUnit.SECONDS));
            assertFalse(secondAcquired.await(200, TimeUnit.MILLISECONDS),
                    "second project lifecycle must not acquire while the first session owns the advisory lock");

            releaseFirst.countDown();
            first.get(10, TimeUnit.SECONDS);
            assertTrue(secondAcquired.await(10, TimeUnit.SECONDS));
            second.get(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Waits until {@code thread} is parked (waiting or timed waiting) in {@link ProjectMutationIndexStateStore},
     * where a lifecycle lease waits for the local project gate; fails if a waiter completes first or when the
     * deadline expires.
     */
    private static void awaitParkedInStore(Thread thread, List<Future<?>> waiters, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!parkedInStore(thread)) {
            if (waiters.stream().anyMatch(Future::isDone)) {
                fail("a same-project lifecycle waiter completed while the first lease was held");
            }
            if (System.nanoTime() - deadline >= 0L) {
                fail(thread.getName() + " was not observed waiting for the project lifecycle lease within " + timeout);
            }
            Thread.onSpinWait();
        }
    }

    private static boolean parkedInStore(Thread thread) {
        Thread.State state = thread.getState();
        if (state != Thread.State.WAITING && state != Thread.State.TIMED_WAITING) return false;
        for (StackTraceElement frame : thread.getStackTrace()) {
            if (frame.getClassName().equals(ProjectMutationIndexStateStore.class.getName())) return true;
        }
        return false;
    }

    private static ProjectIndexState ready(UUID id) {
        return new ProjectIndexState(id, ProjectIndexState.Availability.READY,
                Optional.of("snapshot-1"), Optional.empty(), Instant.EPOCH, Optional.empty());
    }

    private static final class RecordingStore implements IndexStateStore {
        private final AtomicReference<ProjectIndexState> state = new AtomicReference<>();
        public Optional<ProjectIndexState> findProjectState(UUID id) { return Optional.ofNullable(state.get()); }
        public Optional<IndexingRun> findRun(UUID id) { return Optional.empty(); }
        public List<IndexingRun> listRuns(UUID id) { return List.of(); }
        public void saveProjectState(ProjectIndexState value) { state.set(value); }
        public void saveRun(IndexingRun run) {
            // This delegate records only project-state mutations for the synchronization tests above.
        }
    }
}
