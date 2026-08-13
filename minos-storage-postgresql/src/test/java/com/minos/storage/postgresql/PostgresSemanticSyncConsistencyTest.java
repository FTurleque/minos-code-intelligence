package com.minos.storage.postgresql;

import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore;
import com.minos.semantic.StaleSemanticSyncException;
import org.junit.jupiter.api.Test;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that semantic replacement and structural snapshot promotion use the same
 * transaction-scoped per-project advisory lock so stale writers cannot commit across a promotion.
 */
class PostgresSemanticSyncConsistencyTest extends PostgresTestSupport {

    @Test
    void replaceConditionallyThrowsWhenActiveSnapshotChanged() throws Exception {
        String projectId = UUID.randomUUID().toString();
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);

        store.replace(snap(projectId, "snap-2"));

        Exception ex = assertThrows(StaleSemanticSyncException.class,
                () -> store.replaceConditionally(snap(projectId, "snap-1"), "snap-1",
                        () -> Optional.of("snap-2")));
        assertTrue(ex.getMessage().contains("snap-2"));
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
            Future<?> futureA = executor.submit(() -> {
                try {
                    store.replaceConditionally(snap(projectId, "snap-1"), "snap-1", () -> {
                        aHoldsAdvisory.countDown();
                        try {
                            releaseA.await(15, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return Optional.of("snap-2");
                    });
                } catch (StaleSemanticSyncException e) {
                    aSawStale.set(true);
                } catch (Throwable t) {
                    errors.compareAndSet(null, t);
                }
            });

            assertTrue(aHoldsAdvisory.await(15, TimeUnit.SECONDS));

            Future<?> futureB = executor.submit(() -> {
                try {
                    store.replaceConditionally(snap(projectId, "snap-2"), "snap-2",
                            () -> Optional.of("snap-2"));
                    bSucceeded.set(true);
                } catch (Throwable t) {
                    errors.compareAndSet(null, t);
                }
            });

            releaseA.countDown();
            futureA.get(15, TimeUnit.SECONDS);
            futureB.get(15, TimeUnit.SECONDS);
        }

        assertTrue(aSawStale.get());
        assertTrue(bSucceeded.get());
        assertTrue(errors.get() == null, "no unexpected errors: " + errors.get());
        assertEquals("snap-2", store.metadata(projectId).orElseThrow().snapshotId());
    }

    @Test
    void structuralPromotionCannotInterleaveBetweenSemanticRecheckAndCommit() throws Exception {
        UUID projectId = UUID.randomUUID();
        String projectText = projectId.toString();
        PostgresCodeKnowledgeSnapshotStore snapshots = new PostgresCodeKnowledgeSnapshotStore(connections);
        PostgresSemanticVectorStore semantic = new PostgresSemanticVectorStore(connections);
        snapshots.publish(projectId, "snap-1", List.of(), List.of(), List.of());

        CountDownLatch semanticHoldsAdvisory = new CountDownLatch(1);
        CountDownLatch releaseSemantic = new CountDownLatch(1);
        AtomicBoolean structuralPromoted = new AtomicBoolean(false);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> semanticCommit = executor.submit(() -> {
                try {
                    semantic.replaceConditionally(snap(projectText, "snap-1"), "snap-1", () -> {
                        semanticHoldsAdvisory.countDown();
                        try {
                            releaseSemantic.await(15, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new java.io.IOException("semantic test interrupted", interrupted);
                        }
                        return snapshots.loadActiveKnowledge(projectId).map(value -> value.snapshotId());
                    });
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                }
            });

            assertTrue(semanticHoldsAdvisory.await(15, TimeUnit.SECONDS),
                    "semantic transaction must own the advisory project lock before promotion starts");

            Future<?> structuralPromotion = executor.submit(() -> {
                try {
                    snapshots.publish(projectId, "snap-2", List.of(), List.of(), List.of());
                    structuralPromoted.set(true);
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                }
            });

            Thread.sleep(200);
            assertFalse(structuralPromoted.get(),
                    "structural promotion must block while semantic recheck and commit own the advisory lock");

            releaseSemantic.countDown();
            semanticCommit.get(15, TimeUnit.SECONDS);
            structuralPromotion.get(15, TimeUnit.SECONDS);
        }

        assertTrue(failure.get() == null, "no unexpected concurrency error: " + failure.get());
        assertTrue(structuralPromoted.get());
        assertEquals("snap-1", semantic.metadata(projectText).orElseThrow().snapshotId());
        assertEquals("snap-2", snapshots.loadActiveKnowledge(projectId).orElseThrow().snapshotId());
    }

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
                        try {
                            xProceed.await(15, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return Optional.of("snap-x");
                    });
                } catch (Throwable t) {
                    errors.compareAndSet(null, t);
                }
            });

            assertTrue(xHoldsLock.await(10, TimeUnit.SECONDS));

            Future<?> futureY = executor.submit(() -> {
                try {
                    store.replaceConditionally(snap(projectY, "snap-y"), "snap-y",
                            () -> Optional.of("snap-y"));
                    yDone.set(true);
                } catch (Throwable t) {
                    errors.compareAndSet(null, t);
                }
            });
            futureY.get(15, TimeUnit.SECONDS);

            assertTrue(yDone.get());
            assertTrue(errors.get() == null, "no unexpected errors: " + errors.get());

            xProceed.countDown();
            futureX.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void advisoryLockIsReleasedAfterStaleException() throws Exception {
        String projectId = UUID.randomUUID().toString();
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);

        try {
            store.replaceConditionally(snap(projectId, "snap-1"), "snap-1",
                    () -> Optional.of("snap-2"));
        } catch (StaleSemanticSyncException ignored) {
        }

        store.replaceConditionally(snap(projectId, "snap-2"), "snap-2",
                () -> Optional.of("snap-2"));
        assertEquals("snap-2", store.metadata(projectId).orElseThrow().snapshotId());
    }

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
