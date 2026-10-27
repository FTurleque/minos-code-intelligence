package com.minos.storage.postgresql;

import com.minos.storage.postgresql.support.PostgresProjectMutationLock;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code pg_locks} probe of {@link PostgresTestSupport#awaitAdvisoryLockWaiter} proves a waiter when there is
 * one and fails, with a message, when the deadline expires without one (AUD-TST-10).
 */
class PostgresAdvisoryLockWaiterTest extends PostgresTestSupport {

    private static final Duration SHORT = Duration.ofMillis(300);

    @Test
    void observesASessionWaitingForTheProjectLock() throws Exception {
        UUID projectId = UUID.randomUUID();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> holder = pool.submit(() -> holdProjectLock(projectId, held, release));
            assertTrue(held.await(10, TimeUnit.SECONDS));
            Future<?> waiter = pool.submit(() -> holdProjectLock(projectId, new CountDownLatch(1), new CountDownLatch(0)));

            awaitAdvisoryLockWaiter(projectId, Duration.ofSeconds(10));

            assertFalse(waiter.isDone(), "the observed waiter is still blocked by the holder");
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            waiter.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void failsAtTheDeadlineWhenTheLockIsHeldButNobodyWaits() throws Exception {
        UUID projectId = UUID.randomUUID();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            Future<?> holder = pool.submit(() -> holdProjectLock(projectId, held, release));
            assertTrue(held.await(10, TimeUnit.SECONDS));
            try {
                AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                        () -> awaitAdvisoryLockWaiter(projectId, SHORT));

                assertTrue(failure.getMessage().contains(
                        "no session was observed waiting for the advisory lock of project " + projectId),
                        failure.getMessage());
            } finally {
                release.countDown();
                holder.get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void ignoresASessionWaitingForAnotherProject() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID otherProject = UUID.randomUUID();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> holder = pool.submit(() -> holdProjectLock(otherProject, held, release));
            assertTrue(held.await(10, TimeUnit.SECONDS));
            Future<?> waiter = pool.submit(() -> holdProjectLock(otherProject, new CountDownLatch(1), new CountDownLatch(0)));
            try {
                awaitAdvisoryLockWaiter(otherProject, Duration.ofSeconds(10));

                assertThrows(AssertionFailedError.class, () -> awaitAdvisoryLockWaiter(projectId, SHORT));
            } finally {
                release.countDown();
                holder.get(10, TimeUnit.SECONDS);
                waiter.get(10, TimeUnit.SECONDS);
            }
        }
    }

    private Void holdProjectLock(UUID projectId, CountDownLatch held, CountDownLatch release) throws Exception {
        connections.inTransaction(connection -> {
            PostgresProjectMutationLock.acquire(connection, projectId);
            held.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test timed out while holding the project lock");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("test interrupted", interrupted);
            }
            return null;
        });
        return null;
    }
}
