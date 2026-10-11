package com.minos.storage.postgresql.project;

import com.minos.orchestration.InMemoryIndexStateStore;
import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.ProjectIndexState;
import com.minos.storage.postgresql.PostgresTestSupport;
import com.minos.storage.postgresql.support.PostgresConnectionFactory;
import com.minos.storage.postgresql.support.PostgresJsonCodec;
import com.minos.storage.postgresql.support.PostgresProjectMutationLock;
import com.minos.storage.postgresql.support.PostgresTestPools;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Refusal paths of the project lifecycle lease: every acquisition that cannot complete fails closed and gives
 * back everything it had reserved (local gate, dedicated session, advisory lock, thread binding).
 */
class ProjectMutationIndexStateStoreRefusalTest extends PostgresTestSupport {
    private static final Duration SHORT_ACQUIRE_TIMEOUT = Duration.ofMillis(300);
    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");

    @TempDir Path home;

    /** The base class opens one pool per test and never closes it: release its idle session with the test. */
    @AfterEach
    void closeSharedPool() {
        if (connections != null) connections.close();
    }

    @Test
    void readsAreDelegatedAndWritesReachTheDelegateThroughTheProjectMutation() {
        UUID projectId = UUID.randomUUID();
        ProjectMutationIndexStateStore store = new ProjectMutationIndexStateStore(
                connections, new PostgresIndexStateStore(connections, new PostgresJsonCodec()));
        IndexingRun run = running(projectId);
        ProjectIndexState state = ProjectIndexState.neverIndexed(projectId, NOW);

        store.saveRun(run);
        store.saveProjectState(state);

        assertEquals(Optional.of(run), store.findRun(run.id()));
        assertEquals(List.of(run), store.listRuns(projectId));
        assertEquals(Optional.of(state), store.findProjectState(projectId));
        assertEquals(Optional.empty(), store.findRun(UUID.randomUUID()));
        assertEquals(0, connections.poolStats().leased(), "a mutation must release its transaction connection");
    }

    @Test
    void missingArgumentsAreRefused() {
        InMemoryIndexStateStore delegate = new InMemoryIndexStateStore();
        ProjectMutationIndexStateStore store = new ProjectMutationIndexStateStore(connections, delegate);

        assertThrows(NullPointerException.class, () -> new ProjectMutationIndexStateStore(null, delegate));
        assertThrows(NullPointerException.class, () -> new ProjectMutationIndexStateStore(connections, null));
        assertThrows(NullPointerException.class, () -> store.acquireProjectLease(null));
        assertThrows(NullPointerException.class, () -> store.saveProjectState(null));
        assertThrows(NullPointerException.class, () -> store.saveRun(null));
        assertEquals(0, connections.poolStats().dedicated());
    }

    @Test
    void aThreadHoldingOneProjectLeaseIsRefusedASecondProjectAndKeepsTheFirst() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ProjectMutationIndexStateStore store = new ProjectMutationIndexStateStore(
                connections, new InMemoryIndexStateStore());

        try (IndexStateStore.ProjectLease _ = store.acquireProjectLease(first)) {
            IllegalStateException refusal = assertThrows(IllegalStateException.class,
                    () -> store.acquireProjectLease(second));

            assertEquals("cannot acquire a second PostgreSQL project lifecycle lease on the same thread: held="
                    + first + " requested=" + second, refusal.getMessage());
            assertEquals(1, connections.poolStats().dedicated(), "the refused lease must not reserve a session");
            store.saveProjectState(ProjectIndexState.neverIndexed(first, NOW));
        }

        assertEquals(0, connections.poolStats().dedicated());
        try (IndexStateStore.ProjectLease _ = store.acquireProjectLease(second)) {
            assertEquals(1, connections.poolStats().dedicated(), "the second project is free once the first is released");
        }
    }

    @Test
    void aClosedPoolRefusesTheLeaseAndEveryMutationWithoutTouchingTheDelegate() throws Exception {
        PostgresConnectionFactory closed = createFactory("minos");
        closed.close();
        InMemoryIndexStateStore delegate = new InMemoryIndexStateStore();
        ProjectMutationIndexStateStore store = new ProjectMutationIndexStateStore(closed, delegate);
        UUID projectId = UUID.randomUUID();
        ProjectIndexState state = ProjectIndexState.neverIndexed(projectId, NOW);
        IndexingRun run = running(projectId);

        IllegalStateException lease = assertThrows(IllegalStateException.class, () -> store.acquireProjectLease(projectId));
        IllegalStateException saveState = assertThrows(IllegalStateException.class, () -> store.saveProjectState(state));
        IllegalStateException saveRun = assertThrows(IllegalStateException.class, () -> store.saveRun(run));

        assertEquals("PostgreSQL lifecycle lease could not reserve a dedicated connection", lease.getMessage());
        assertEquals("PostgreSQL index state mutation failed to save project state", saveState.getMessage());
        assertEquals("PostgreSQL index state mutation failed to save indexing run", saveRun.getMessage());
        assertInstanceOf(SQLException.class, lease.getCause());
        assertEquals(Optional.empty(), delegate.findProjectState(projectId), "an unlocked mutation must never reach the delegate");
        assertEquals(List.of(), delegate.listRuns(projectId));
    }

    @Test
    void aLeaseHeldByAnotherSessionTimesOutAndGivesBackItsDedicatedSession() throws Exception {
        UUID projectId = UUID.randomUUID();
        try (PostgresConnectionFactory pool = PostgresTestPools.sized(connections, home, 2, SHORT_ACQUIRE_TIMEOUT);
             PostgresConnectionFactory.DedicatedConnectionLease competitor = connections.openDedicatedConnection()) {
            ProjectMutationIndexStateStore store = new ProjectMutationIndexStateStore(pool, new InMemoryIndexStateStore());
            assertTrue(advisory(competitor.connection(), "pg_try_advisory_lock", projectId));

            IllegalStateException refusal = assertThrows(IllegalStateException.class,
                    () -> store.acquireProjectLease(projectId));

            assertEquals("timed out waiting for PostgreSQL project lifecycle lease after "
                    + SHORT_ACQUIRE_TIMEOUT + ": " + projectId, refusal.getMessage());
            assertEquals(0, pool.poolStats().dedicated(), "the waiter must close the session it reserved");
            assertEquals(0, pool.poolStats().leased());

            assertTrue(advisory(competitor.connection(), "pg_advisory_unlock", projectId),
                    "the competitor must still own the lock the waiter could not take");
            try (IndexStateStore.ProjectLease _ = store.acquireProjectLease(projectId)) {
                assertEquals(1, pool.poolStats().dedicated(), "the lease is granted once the other session lets go");
            }
        }
    }

    @Test
    void anExhaustedDedicatedBudgetRefusesTheLeaseAndReleasesTheLocalGate() throws Exception {
        UUID projectId = UUID.randomUUID();
        try (PostgresConnectionFactory pool = PostgresTestPools.sized(connections, home, 1, SHORT_ACQUIRE_TIMEOUT)) {
            ProjectMutationIndexStateStore store = new ProjectMutationIndexStateStore(pool, new InMemoryIndexStateStore());
            PostgresConnectionFactory.DedicatedConnectionLease onlySlot = pool.openDedicatedConnection();

            IllegalStateException refusal = assertThrows(IllegalStateException.class,
                    () -> store.acquireProjectLease(projectId));

            assertEquals("PostgreSQL lifecycle lease could not reserve a dedicated connection", refusal.getMessage());
            assertInstanceOf(SQLException.class, refusal.getCause());
            assertEquals(1L, pool.poolStats().dedicatedAcquisitionTimeouts());
            assertEquals(1, pool.poolStats().dedicated(), "only the slot held by the test remains");

            onlySlot.close();
            try (IndexStateStore.ProjectLease _ = store.acquireProjectLease(projectId)) {
                assertEquals(1, pool.poolStats().dedicated());
            }
        }
    }

    @Test
    void aThreadAlreadyMarkedAsOwningAnotherLifecycleIsRefusedAndTheAdvisoryLockIsReleased() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID foreignProject = UUID.randomUUID();
        ProjectMutationIndexStateStore store = new ProjectMutationIndexStateStore(
                connections, new InMemoryIndexStateStore());

        try (PostgresConnectionFactory.DedicatedConnectionLease foreign = connections.openDedicatedConnection()) {
            Connection foreignSession = foreign.connection();
            PostgresProjectMutationLock.enterLifecycle(foreignProject, foreignSession);
            try {
                IllegalStateException refusal = assertThrows(IllegalStateException.class,
                        () -> store.acquireProjectLease(projectId));

                assertEquals("thread already owns a PostgreSQL lifecycle lock for another project or connection: "
                        + foreignProject, refusal.getMessage());
                assertEquals(1, connections.poolStats().dedicated(), "only the session opened by the test remains");
                assertTrue(advisory(foreignSession, "pg_try_advisory_lock", projectId),
                        "the refused acquisition must have released the advisory lock it had taken");
                assertTrue(advisory(foreignSession, "pg_advisory_unlock", projectId));
                assertEquals(1, selectOne(connections), "the thread binding must be removed: ordinary work uses the pool");
                assertEquals(0, connections.poolStats().leased());
            } finally {
                PostgresProjectMutationLock.exitLifecycle(foreignProject, foreignSession);
            }
        }

        try (IndexStateStore.ProjectLease _ = store.acquireProjectLease(projectId)) {
            assertEquals(1, connections.poolStats().dedicated());
        }
    }

    @Test
    void anInterruptedThreadIsRefusedTheLeaseAndKeepsItsInterruptFlag() throws Exception {
        UUID projectId = UUID.randomUUID();
        ProjectMutationIndexStateStore store = new ProjectMutationIndexStateStore(
                connections, new InMemoryIndexStateStore());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean stillInterrupted = new AtomicBoolean();

        Thread interrupted = Thread.ofPlatform().start(() -> {
            Thread.currentThread().interrupt();
            try {
                store.acquireProjectLease(projectId);
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
            stillInterrupted.set(Thread.currentThread().isInterrupted());
        });
        interrupted.join();

        IllegalStateException refusal = assertInstanceOf(IllegalStateException.class, failure.get());
        assertEquals("interrupted while waiting for local PostgreSQL lifecycle gate: " + projectId, refusal.getMessage());
        assertInstanceOf(InterruptedException.class, refusal.getCause());
        assertTrue(stillInterrupted.get(), "the interrupt flag must be restored for the caller");
        assertEquals(0, connections.poolStats().dedicated(), "no session may be reserved by an interrupted waiter");
        try (IndexStateStore.ProjectLease _ = store.acquireProjectLease(projectId)) {
            assertEquals(1, connections.poolStats().dedicated(), "the gate must be free for the next caller");
        }
    }

    @Test
    void aSecondThreadOfTheSameProcessTimesOutOnTheLocalGateWithoutReservingASession() throws Exception {
        UUID projectId = UUID.randomUUID();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (PostgresConnectionFactory pool = PostgresTestPools.sized(connections, home, 2, SHORT_ACQUIRE_TIMEOUT);
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            ProjectMutationIndexStateStore store = new ProjectMutationIndexStateStore(pool, new InMemoryIndexStateStore());
            Future<Void> holder = executor.submit(() -> {
                try (IndexStateStore.ProjectLease _ = store.acquireProjectLease(projectId)) {
                    held.countDown();
                    if (!release.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test timed out while holding the lifecycle lease");
                    }
                }
                return null;
            });
            try {
                assertTrue(held.await(30, TimeUnit.SECONDS));

                IllegalStateException refusal = assertThrows(IllegalStateException.class,
                        () -> store.acquireProjectLease(projectId));

                assertEquals("timed out waiting for local PostgreSQL lifecycle gate after "
                        + SHORT_ACQUIRE_TIMEOUT + ": " + projectId, refusal.getMessage());
                assertEquals(1, pool.poolStats().dedicated(),
                        "a waiter stopped at the local gate must not have reserved a dedicated session");
                assertEquals(0L, pool.poolStats().dedicatedAcquisitionTimeouts());
            } finally {
                release.countDown();
            }
            holder.get(30, TimeUnit.SECONDS);

            try (IndexStateStore.ProjectLease _ = store.acquireProjectLease(projectId)) {
                assertEquals(1, pool.poolStats().dedicated(), "the gate is free once the holder released it");
            }
        }
    }

    private static boolean advisory(Connection session, String function, UUID projectId) throws SQLException {
        try (PreparedStatement statement = session.prepareStatement("SELECT " + function + "(?)")) {
            statement.setLong(1, PostgresProjectMutationLock.key(projectId));
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static int selectOne(PostgresConnectionFactory pool) throws Exception {
        return pool.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT 1");
                 ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        });
    }

    private static IndexingRun running(UUID projectId) {
        return new IndexingRun(UUID.randomUUID(), projectId, IndexingRun.Status.RUNNING,
                IndexingRun.Phase.PROVIDER_EXECUTION, NOW, Optional.empty(), List.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), IndexingRun.CURRENT_FORMAT_VERSION);
    }
}
