package com.minos.storage.local.store;

import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore;
import com.minos.testsupport.ParkedThreads;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Read delegation and the delete path of the semantic decorator, which shares the structural mutation lease. */
class ProjectMutationSemanticVectorStoreDelegationTest {
    private static final Duration WAIT = Duration.ofSeconds(10);

    @Test
    void refusesMissingCollaborators(@TempDir Path temp) {
        SemanticVectorStore delegate = new RecordingStore();
        NullPointerException root = assertThrows(NullPointerException.class,
                () -> new ProjectMutationSemanticVectorStore(null, delegate));
        assertEquals("storageRoot", root.getMessage());
        NullPointerException missing = assertThrows(NullPointerException.class,
                () -> new ProjectMutationSemanticVectorStore(temp, null));
        assertEquals("delegate", missing.getMessage());
    }

    @Test
    void searchSizeAndDeleteReachTheFileStoreAndDeleteRemovesTheIndex(@TempDir Path temp) throws Exception {
        Path semanticRoot = temp.resolve("storage").resolve("semantic-index");
        FileSemanticVectorStore files = new FileSemanticVectorStore(semanticRoot);
        SemanticVectorStore store = new ProjectMutationSemanticVectorStore(semanticRoot, files);
        String project = UUID.randomUUID().toString();
        store.replace(snapshot(project, "snap-1"));

        List<SemanticVectorStore.VectorHit> hits =
                store.search(project, new SemanticVector("query", List.of(1.0, 0.0)), 5, 0.5);
        assertEquals(List.of("id:snap-1"), hits.stream().map(hit -> hit.document().id()).toList());
        assertEquals(files.searchEngine(), store.searchEngine());
        long size = store.sizeBytes(project);
        assertTrue(size > 0L, "a stored index has a size on disk");
        assertEquals(files.sizeBytes(project), size);

        store.delete(project);

        assertTrue(store.load(project).isEmpty(), "delete must remove the semantic index");
        assertEquals(0L, store.sizeBytes(project));
        assertTrue(store.search(project, new SemanticVector("query", List.of(1.0, 0.0)), 5, 0.5).isEmpty());
    }

    @Test
    void deleteRefusesAnUnsafeProjectIdBeforeTheDelegateRuns(@TempDir Path temp) {
        RecordingStore delegate = new RecordingStore();
        SemanticVectorStore store = new ProjectMutationSemanticVectorStore(temp.resolve("semantic-index"), delegate);

        assertThrows(IllegalArgumentException.class, () -> store.delete("../escape"));

        assertTrue(delegate.deleted.isEmpty(), "an unsafe project id must never reach the delegate");
    }

    @RepeatedTest(50)
    void deleteWaitsForTheSharedProjectMutationLease(@TempDir Path temp) throws Exception {
        Path semanticRoot = temp.resolve("storage").resolve("semantic-index");
        RecordingStore delegate = new RecordingStore();
        SemanticVectorStore store = new ProjectMutationSemanticVectorStore(semanticRoot, delegate);
        String project = UUID.randomUUID().toString();
        AtomicReference<Thread> deleter = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> deletion;
            try (SnapshotProjectLease held = SnapshotProjectLease.acquire(semanticRoot, project)) {
                deletion = executor.submit(() -> {
                    deleter.set(Thread.currentThread());
                    started.countDown();
                    store.delete(project);
                    return null;
                });
                assertTrue(started.await(WAIT.toSeconds(), TimeUnit.SECONDS));
                ParkedThreads.awaitParkedIn(deleter.get(), "com.minos.io.BoundedFileLease", deletion::isDone, WAIT);
                assertTrue(delegate.deleted.isEmpty(), "delete must not run while another mutation holds the lease");
                assertFalse(deletion.isDone());
            }
            assertNull(deletion.get(WAIT.toSeconds(), TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertEquals(List.of(project), delegate.deleted);
    }

    private static SemanticVectorStore.IndexSnapshot snapshot(String projectId, String snapshotId) {
        String stableKey = "symbol:fixture:" + snapshotId;
        SemanticDocument document = new SemanticDocument(
                "id:" + snapshotId, stableKey, projectId, snapshotId, SemanticDocumentKind.SYMBOL, "fixture",
                "src/Fixture.java", 1, 1, "content for " + snapshotId, "checksum-" + snapshotId);
        SemanticVector vector = new SemanticVector(stableKey, List.of(1.0, 0.0));
        return new SemanticVectorStore.IndexSnapshot(projectId, snapshotId, "provider", "model-v1", 2,
                1_700_000_000_000L, List.of(new SemanticVectorStore.IndexedDocument(document, vector)));
    }

    /** Delegate that only records the deletions it receives. */
    private static final class RecordingStore implements SemanticVectorStore {
        private final List<String> deleted = Collections.synchronizedList(new ArrayList<>());

        @Override
        public Optional<IndexSnapshot> load(String projectId) {
            return Optional.empty();
        }

        @Override
        public void replace(IndexSnapshot snapshot) {
            // Not exercised: only the delete path is observed.
        }

        @Override
        public void replaceConditionally(
                IndexSnapshot next, String expectedActiveSnapshotId, ActiveSnapshotIdReader activeSnapshotReader) {
            // Not exercised: only the delete path is observed.
        }

        @Override
        public void delete(String projectId) throws IOException {
            deleted.add(projectId);
        }
    }
}
