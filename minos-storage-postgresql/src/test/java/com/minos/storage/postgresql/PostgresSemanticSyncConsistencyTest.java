package com.minos.storage.postgresql;

import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore;
import com.minos.semantic.StaleSemanticSyncException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link PostgresSemanticVectorStore#replaceConditionally} uses a
 * transaction-scoped per-project advisory lock to prevent stale writers from committing.
 */
class PostgresSemanticSyncConsistencyTest extends PostgresTestSupport {

    // -------------------------------------------------------------------------
    // Stale writer is rejected (advisory lock + conditional check)
    // -------------------------------------------------------------------------

    @Test
    void replaceConditionallyThrowsWhenActiveSnapshotChanged() throws Exception {
        String projectId = UUID.randomUUID().toString();
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);

        // Commit snap-2 as the current durable index
        store.replace(snap(projectId, "snap-2"));

        // Try to commit snap-1 while active is snap-2 → must be rejected
        Exception ex = org.junit.jupiter.api.Assertions.assertThrows(StaleSemanticSyncException.class,
                () -> store.replaceConditionally(snap(projectId, "snap-1"), "snap-1",
                        () -> Optional.of("snap-2")));
        assertTrue(ex.getMessage().contains("snap-2"), "message should name the promoted snapshot");

        // Durable index is still snap-2
        assertEquals("snap-2", store.metadata(projectId).orElseThrow().snapshotId());
    }

    @Test
    void replaceConditionallySucceedsWhenSnapshotIsStillCurrent() throws Exception {
        String projectId = UUID.randomUUID().toString();
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);

        store.replaceConditionally(snap(projectId, "snap-1"), "snap-1",
                () -> Optional.of("snap-1"));

        assertEquals("snap-1", store.metadata(projectId).orElseThrow().snapshotId());
    }

    // -------------------------------------------------------------------------
    // Advisory lock serializes concurrent writers for the same project
    // -------------------------------------------------------------------------

    @Test
    void sameProjectWritersAreSerializedByAdvisoryLock() throws Exception {
        String projectId = UUID.randomUUID().toString();
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);

        AtomicBoolean aSawStale = new AtomicBoolean(false);
        AtomicBoolean bSucceeded = new AtomicBoolean(false);
        CountDownLatch aHoldsAdvisory = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        AtomicReference<Throwable> errors = new AtomicReference<>();

        try (var executor = Executors.newFixedThreadPool(2)) {
            // Thread A: holds the advisory lock while reader blocks on latch
            Future<?> futureA = executor.submit(() -> {
                try {
                    store.replaceConditionally(snap(projectId, "snap-1"), "snap-1", () -> {
                        aHoldsAdvisory.countDown();
                        try { releaseA.await(15, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        // Active has been promoted to snap-2 while A was building
                        return Optional.of("snap-2");
                    });
                } catch (StaleSemanticSyncException e) {
                    aSawStale.set(true);
                } catch (Throwable t) {
                    errors.compareAndSet(null, t);
                }
            });

            assertTrue(aHoldsAdvisory.await(15, TimeUnit.SECONDS),
                    "thread A must enter the advisory lock before we release it");

            // Thread B: tries to commit snap-2 while A holds the advisory lock → waits in DB
            Future<?> futureB = executor.submit(() -> {
                try {
                    store.replaceConditionally(snap(projectId, "snap-2"), "snap-2",
                            () -> Optional.of("snap-2"));
                    bSucceeded.set(true);
                } catch (Throwable t) {
                    errors.compareAndSet(null, t);
                }
            });

            // Release A → advisory lock released on rollback (StaleSemanticSyncException)
            releaseA.countDown();
            futureA.get(15, TimeUnit.SECONDS);
            futureB.get(15, TimeUnit.SECONDS);
        }

        assertTrue(aSawStale.get(), "thread A must see StaleSemanticSyncException");
        assertTrue(bSucceeded.get(), "thread B must succeed after A's advisory lock is released on abort");
        assertTrue(errors.get() == null, "no unexpected errors: " + errors.get());
        assertEquals("snap-2", store.metadata(projectId).orElseThrow().snapshotId());
    }

    // -------------------------------------------------------------------------
    // Different projects use independent advisory keys → no serialization
    // -------------------------------------------------------------------------

    @Test
    void differentProjectsDoNotShareAdvisoryLock() throws Exception {
        String projectX = UUID.randomUUID().toString();
        String projectY = UUID.randomUUID().toString();
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);

        CountDownLatch xHoldsLock = new CountDownLatch(1);
        CountDownLatch xProceed = new CountDownLatch(1);
        AtomicBoolean yDone = new AtomicBoolean(false);
        AtomicReference<Throwable> errors = new AtomicReference<>();

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> futureX = executor.submit(() -> {
                try {
                    store.replaceConditionally(snap(projectX, "snap-x"), "snap-x", () -> {
                        xHoldsLock.countDown();
                        try { xProceed.await(15, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        return Optional.of("snap-x");
                    });
                } catch (Throwable t) { errors.compareAndSet(null, t); }
            });

            assertTrue(xHoldsLock.await(10, TimeUnit.SECONDS));

            Future<?> futureY = executor.submit(() -> {
                try {
                    store.replaceConditionally(snap(projectY, "snap-y"), "snap-y",
                            () -> Optional.of("snap-y"));
                    yDone.set(true);
                } catch (Throwable t) { errors.compareAndSet(null, t); }
            });
            futureY.get(15, TimeUnit.SECONDS);

            assertTrue(yDone.get(), "project Y must complete while X holds its advisory lock");
            assertTrue(errors.get() == null, "no unexpected errors: " + errors.get());

            xProceed.countDown();
            futureX.get(10, TimeUnit.SECONDS);
        }
    }

    // -------------------------------------------------------------------------
    // Advisory lock is released on abort (no leak after exception)
    // -------------------------------------------------------------------------

    @Test
    void advisoryLockIsReleasedAfterStaleException() throws Exception {
        String projectId = UUID.randomUUID().toString();
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);

        // First call is stale → advisory lock must be released on transaction rollback
        try {
            store.replaceConditionally(snap(projectId, "snap-1"), "snap-1",
                    () -> Optional.of("snap-2")); // stale
        } catch (StaleSemanticSyncException ignored) {}

        // Second call must not be blocked by a leaked advisory lock
        store.replaceConditionally(snap(projectId, "snap-2"), "snap-2",
                () -> Optional.of("snap-2"));
        assertEquals("snap-2", store.metadata(projectId).orElseThrow().snapshotId(),
                "advisory lock must be released on rollback — follow-up commit must succeed");
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static SemanticVectorStore.IndexSnapshot snap(String projectId, String snapshotId) {
        String stableKey = "symbol:pg:" + snapshotId;
        SemanticDocument document = new SemanticDocument(
                "id-" + snapshotId, stableKey, projectId, snapshotId,
                SemanticDocumentKind.SYMBOL, "pg-fixture", "src/Fixture.java",
                1, 1, "pg content " + snapshotId, "cs-" + snapshotId);
        SemanticVector vector = SemanticVector.fromArray(stableKey, new double[]{1.0, 0.0, 0.0});
        return new SemanticVectorStore.IndexSnapshot(
                projectId, snapshotId, "provider", "model-v1", 3,
                System.currentTimeMillis(),
                List.of(new SemanticVectorStore.IndexedDocument(document, vector)));
    }
}
