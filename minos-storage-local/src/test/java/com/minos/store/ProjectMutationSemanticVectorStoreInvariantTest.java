package com.minos.store;

import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectMutationSemanticVectorStoreInvariantTest {

    @Test
    void siblingStorageRootsResolveToTheSameProjectMutationLease(@TempDir Path home) throws Exception {
        String projectId = UUID.randomUUID().toString();

        Path structuralLock = SnapshotProjectLease.lockFile(home.resolve("symbol-snapshots"), projectId);
        Path semanticLock = SnapshotProjectLease.lockFile(home.resolve("semantic-index"), projectId);

        assertEquals(structuralLock, semanticLock,
                "structural promotion and semantic commit must share one cross-JVM project lock");
    }

    @Test
    void structuralPromotionCannotInterleaveBetweenSemanticRecheckAndCommit(@TempDir Path home) throws Exception {
        Path structuralRoot = home.resolve("symbol-snapshots");
        Path semanticRoot = home.resolve("semantic-index");
        UUID projectId = UUID.randomUUID();
        String semanticProjectId = projectId.toString();

        FileSymbolSnapshotStore structuralStore = new FileSymbolSnapshotStore(structuralRoot);
        structuralStore.publish(projectId, "snap-1", List.of(), List.of(), List.of());
        ProjectMutationSemanticVectorStore semanticStore = new ProjectMutationSemanticVectorStore(
                semanticRoot, new FileSemanticVectorStore(semanticRoot));

        CountDownLatch activeReadEntered = new CountDownLatch(1);
        CountDownLatch allowSemanticCommit = new CountDownLatch(1);
        CountDownLatch structuralAttempted = new CountDownLatch(1);
        AtomicInteger completionOrder = new AtomicInteger();
        AtomicInteger semanticCompleted = new AtomicInteger();
        AtomicInteger structuralCompleted = new AtomicInteger();

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> semantic = executor.submit(() -> {
                semanticStore.replaceConditionally(
                        snapshot(semanticProjectId, "snap-1"),
                        "snap-1",
                        () -> {
                            activeReadEntered.countDown();
                            await(allowSemanticCommit);
                            return structuralStore.loadActiveKnowledge(projectId)
                                    .map(CodeKnowledgeSnapshot::snapshotId);
                        });
                semanticCompleted.set(completionOrder.incrementAndGet());
                return null;
            });

            assertTrue(activeReadEntered.await(5, TimeUnit.SECONDS),
                    "semantic commit must reach the active snapshot recheck");

            Future<?> structural = executor.submit(() -> {
                structuralAttempted.countDown();
                structuralStore.publish(projectId, "snap-2", List.of(), List.of(), List.of());
                structuralCompleted.set(completionOrder.incrementAndGet());
                return null;
            });
            assertTrue(structuralAttempted.await(5, TimeUnit.SECONDS),
                    "structural promotion fixture must start before checking serialization");

            assertThrows(TimeoutException.class, () -> structural.get(250, TimeUnit.MILLISECONDS),
                    "structural promotion must block while semantic recheck/commit holds the shared lease");

            allowSemanticCommit.countDown();
            semantic.get(5, TimeUnit.SECONDS);
            structural.get(5, TimeUnit.SECONDS);
        }

        assertTrue(semanticCompleted.get() > 0);
        assertTrue(structuralCompleted.get() > semanticCompleted.get(),
                "semantic commit must complete before snap-2 can be promoted");
        assertEquals("snap-1", semanticStore.metadata(semanticProjectId).orElseThrow().snapshotId());
        assertEquals("snap-2", structuralStore.loadActiveKnowledge(projectId).orElseThrow().snapshotId());
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IOException("timed out waiting to release semantic commit fixture");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("semantic commit fixture interrupted", exception);
        }
    }

    private static SemanticVectorStore.IndexSnapshot snapshot(String projectId, String snapshotId) {
        String stableKey = "symbol:fixture:" + snapshotId;
        SemanticDocument document = new SemanticDocument(
                "id:" + snapshotId,
                stableKey,
                projectId,
                snapshotId,
                SemanticDocumentKind.SYMBOL,
                "fixture",
                "src/Fixture.java",
                1,
                1,
                "content for " + snapshotId,
                "checksum-" + snapshotId);
        SemanticVector vector = new SemanticVector(stableKey, List.of(1.0, 0.0));
        return new SemanticVectorStore.IndexSnapshot(
                projectId,
                snapshotId,
                "provider",
                "model-v1",
                2,
                System.currentTimeMillis(),
                List.of(new SemanticVectorStore.IndexedDocument(document, vector)));
    }
}
