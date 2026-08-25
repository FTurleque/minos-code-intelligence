package com.minos.storage.postgresql;

import com.minos.storage.PersistentRetentionPolicy;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresRetentionMutationLockTest extends PostgresTestSupport {

    @Test
    void retentionWaitsForTheSharedProjectMutationBoundary() throws Exception {
        UUID projectId = UUID.randomUUID();
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean compacted = new AtomicBoolean();
        PostgresStorageRetentionService retention = new PostgresStorageRetentionService(connections);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> {
                try {
                    connections.inTransaction(connection -> {
                        PostgresProjectMutationLock.acquire(connection, projectId);
                        lockHeld.countDown();
                        try { release.await(10, TimeUnit.SECONDS); }
                        catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new java.io.IOException("test interrupted", exception);
                        }
                        return null;
                    });
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
            assertTrue(lockHeld.await(10, TimeUnit.SECONDS));
            var compactor = executor.submit(() -> {
                try {
                    retention.compact(projectId, PersistentRetentionPolicy.DEFAULT);
                    compacted.set(true);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
            Thread.sleep(200);
            assertFalse(compacted.get(), "retention must wait for the shared project mutation lock");
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            compactor.get(10, TimeUnit.SECONDS);
        }
        assertTrue(compacted.get());
    }
}
