package com.minos.storage.postgresql;

import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.ProjectIndexState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectMutationIndexStateStoreTest extends PostgresTestSupport {
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
            assertFalse(writer.isDone(), "same-project state mutation must remain blocked by the shared advisory lock");
            assertNull(delegate.state.get());
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            writer.get(10, TimeUnit.SECONDS);
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
            Thread.sleep(150L);
            assertFalse(writer.isDone(), "project mutation must wait for the session lifecycle lease");
            assertNull(delegate.state.get());

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            writer.get(10, TimeUnit.SECONDS);
            assertTrue(delegate.state.get() == state);
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
        public void saveRun(IndexingRun run) { }
    }
}
