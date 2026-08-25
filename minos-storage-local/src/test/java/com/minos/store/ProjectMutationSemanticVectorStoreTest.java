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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectMutationSemanticVectorStoreTest {

    @Test
    void structuralPromotionCannotInterleaveBetweenSemanticRecheckAndCommit(@TempDir Path temp)
            throws Exception {
        Path storageFamily = temp.resolve("storage");
        Path snapshotRoot = storageFamily.resolve("symbol-snapshots");
        Path semanticRoot = storageFamily.resolve("semantic-index");
        FileSymbolSnapshotStore snapshots = new FileSymbolSnapshotStore(snapshotRoot);
        SemanticVectorStore semantic = new ProjectMutationSemanticVectorStore(
                semanticRoot, new FileSemanticVectorStore(semanticRoot));
        UUID projectId = UUID.randomUUID();
        String projectText = projectId.toString();
        snapshots.publish(projectId, "snap-1", List.of());

        CountDownLatch semanticInsideSharedLease = new CountDownLatch(1);
        CountDownLatch releaseSemantic = new CountDownLatch(1);
        AtomicBoolean structuralPromoted = new AtomicBoolean(false);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> semanticCommit = executor.submit(() -> {
                try {
                    semantic.replaceConditionally(snapshot(projectText, "snap-1"), "snap-1", () -> {
                        semanticInsideSharedLease.countDown();
                        try {
                            releaseSemantic.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException("semantic test interrupted", interrupted);
                        }
                        return snapshots.loadActiveKnowledge(projectId).map(CodeKnowledgeSnapshot::snapshotId);
                    });
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                }
            });

            assertTrue(semanticInsideSharedLease.await(10, TimeUnit.SECONDS),
                    "semantic commit must hold the shared project mutation lease before promotion starts");

            Future<?> structuralPromotion = executor.submit(() -> {
                try {
                    snapshots.publish(projectId, "snap-2", List.of());
                    structuralPromoted.set(true);
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                }
            });

            Thread.sleep(200);
            assertFalse(structuralPromoted.get(),
                    "structural promotion must block while semantic recheck and commit own the shared lease");

            releaseSemantic.countDown();
            semanticCommit.get(10, TimeUnit.SECONDS);
            structuralPromotion.get(10, TimeUnit.SECONDS);
        }

        assertTrue(failure.get() == null, "no unexpected concurrency error: " + failure.get());
        assertTrue(structuralPromoted.get());
        assertEquals("snap-1", semantic.metadata(projectText).orElseThrow().snapshotId());
        assertEquals("snap-2", snapshots.loadActiveKnowledge(projectId).orElseThrow().snapshotId());
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
