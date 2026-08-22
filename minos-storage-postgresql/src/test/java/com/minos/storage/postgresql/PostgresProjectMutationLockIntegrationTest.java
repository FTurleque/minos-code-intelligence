package com.minos.storage.postgresql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresProjectMutationLockIntegrationTest extends PostgresTestSupport {

    @TempDir Path tempDir;

    @Test
    void structuralPublicationUsesTheSameProjectMutationKey() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresCodeKnowledgeSnapshotStore snapshots = new PostgresCodeKnowledgeSnapshotStore(connections, tempDir);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean published = new AtomicBoolean();

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

            var publisher = executor.submit(() -> {
                try {
                    snapshots.publish(projectId, "snap-1", List.of(), List.of(), List.of());
                    published.set(true);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
            Thread.sleep(200);
            assertFalse(published.get(), "structural publication must wait for the shared mutation key");
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            publisher.get(10, TimeUnit.SECONDS);
        }
        assertTrue(published.get());
    }
}
