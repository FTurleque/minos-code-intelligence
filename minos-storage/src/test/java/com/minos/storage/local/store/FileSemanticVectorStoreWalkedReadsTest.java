package com.minos.storage.local.store;

import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore;
import com.minos.testsupport.SemanticDocumentReadsContract;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The local store reads the document pages, one document and the nearest neighbours of an index it cannot keep in
 * its cache by walking the index file: the index is never loaded for them, and what they answer is what the loaded
 * index answers. A full decode shared by concurrent readers happens once.
 */
class FileSemanticVectorStoreWalkedReadsTest {

    private static final String PROJECT = "project-1";
    /** A cache ceiling no index of this test fits under, even the smallest. */
    private static final long NARROW = 2_048L;
    private static final int DOCUMENTS = 500;

    @TempDir
    Path temp;

    @Test
    void documentPagesOfAnIndexTheCacheCannotHoldAreReadWithoutLoadingIt() throws Exception {
        Path root = temp.resolve("semantic");
        FileSemanticVectorStore wide = indexOf(root, DOCUMENTS);
        List<SemanticDocument> expected = wide.load(PROJECT).orElseThrow().documents().stream()
                .map(SemanticVectorStore.IndexedDocument::document)
                .sorted(Comparator.comparing(SemanticDocument::stableKey))
                .toList();
        FileSemanticVectorStore narrow = new FileSemanticVectorStore(root, NARROW);

        List<SemanticDocument> walked = new ArrayList<>();
        String after = null;
        boolean more = true;
        int pages = 0;
        while (more) {
            SemanticVectorStore.DocumentPage page = narrow.documentPage(PROJECT, after, 200).orElseThrow();
            pages++;
            assertEquals(wide.metadata(PROJECT).orElseThrow(), page.metadata());
            walked.addAll(page.documents());
            more = page.hasMore();
            if (more) after = page.documents().getLast().stableKey();
        }

        assertEquals(3, pages);
        assertEquals(expected, walked);
        assertEquals(0L, narrow.cacheStats().misses(), "no page decodes the whole index");
        assertEquals(0, narrow.cacheStats().entries());
    }

    @Test
    void aPageStartsAfterAnyKeyWhetherTheWalkIsResumedOrStartedAgain() throws Exception {
        Path root = temp.resolve("semantic");
        indexOf(root, 90);
        FileSemanticVectorStore walking = new FileSemanticVectorStore(root, NARROW);
        FileSemanticVectorStore fresh = new FileSemanticVectorStore(root, NARROW);

        SemanticVectorStore.DocumentPage first = walking.documentPage(PROJECT, null, 30).orElseThrow();
        SemanticVectorStore.DocumentPage resumed = walking.documentPage(PROJECT, "doc-029", 30).orElseThrow();
        SemanticVectorStore.DocumentPage restarted = fresh.documentPage(PROJECT, "doc-029", 30).orElseThrow();
        SemanticVectorStore.DocumentPage elsewhere = walking.documentPage(PROJECT, "doc-010", 5).orElseThrow();
        SemanticVectorStore.DocumentPage beyond = walking.documentPage(PROJECT, "doc-089", 5).orElseThrow();

        assertEquals("doc-029", first.documents().getLast().stableKey());
        assertEquals(restarted, resumed);
        assertEquals("doc-030", resumed.documents().getFirst().stableKey());
        assertTrue(resumed.hasMore());
        assertEquals(List.of("doc-011", "doc-012", "doc-013", "doc-014", "doc-015"),
                elsewhere.documents().stream().map(SemanticDocument::stableKey).toList());
        assertEquals(List.of(), beyond.documents());
        assertFalse(beyond.hasMore());
    }

    @Test
    void aDocumentPageResumedAfterTheIndexWasReplacedReadsTheNewIndexFromItsStart() throws Exception {
        Path root = temp.resolve("semantic");
        indexOf(root, 60);
        FileSemanticVectorStore store = new FileSemanticVectorStore(root, NARROW);
        store.documentPage(PROJECT, null, 20).orElseThrow();

        store.replace(SemanticDocumentReadsContract.snapshot(PROJECT, "snap-2", 9L, documents("snap-2", 30)));
        SemanticVectorStore.DocumentPage page = store.documentPage(PROJECT, "doc-019", 20).orElseThrow();

        assertEquals("snap-2", page.metadata().snapshotId());
        assertEquals(30, page.metadata().documentCount());
        assertEquals(10, page.documents().size());
        assertEquals("doc-020", page.documents().getFirst().stableKey());
        assertTrue(page.documents().stream().allMatch(document -> "snap-2".equals(document.snapshotId())));
        assertFalse(page.hasMore());
    }

    @Test
    void anIndexWhoseDocumentsAreNotInStableKeyOrderIsRefusedWithoutNamingItsLocation() throws Exception {
        Path root = temp.resolve("semantic");
        indexOf(root, 3);
        Path file = root.resolve(PROJECT).resolve("index-v2.bin");
        byte[] bytes = Files.readAllBytes(file);
        byte[] key = {0, 0, 0, 7, 'd', 'o', 'c', '-', '0', '0', '2'};
        bytes[indexOf(bytes, key) + key.length - 1] = '0';
        Files.write(file, bytes);
        FileSemanticVectorStore store = new FileSemanticVectorStore(root, NARROW);

        IOException page = assertThrows(IOException.class, () -> store.documentPage(PROJECT, null, 10));
        IOException detail = assertThrows(IOException.class, () -> store.storedDocument(PROJECT, "doc-003"));

        assertEquals("semantic index is not in stable-key order", page.getMessage());
        assertEquals("semantic index is not in stable-key order", detail.getMessage());
        assertFalse(page.getMessage().contains(temp.toString()));
    }

    @Test
    void aTruncatedIndexIsRefusedByThePageAndTheDocumentReadingsWithoutNamingItsLocation() throws Exception {
        Path root = temp.resolve("semantic");
        indexOf(root, 20);
        Path file = root.resolve(PROJECT).resolve("index-v2.bin");
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(bytes, bytes.length - 7));
        FileSemanticVectorStore store = new FileSemanticVectorStore(root, NARROW);

        IOException page = assertThrows(IOException.class, () -> store.documentPage(PROJECT, null, 50));
        IOException detail = assertThrows(IOException.class, () -> store.storedDocument(PROJECT, "doc-019"));

        assertEquals("truncated semantic index: index-v2.bin", page.getMessage());
        assertEquals("truncated semantic index: index-v2.bin", detail.getMessage());
    }

    @Test
    void aStoredDocumentIsReadAloneWithItsVectorAndTheMetadataOfItsIndex() throws Exception {
        Path root = temp.resolve("semantic");
        FileSemanticVectorStore wide = indexOf(root, DOCUMENTS);
        SemanticVectorStore.IndexSnapshot loaded = wide.load(PROJECT).orElseThrow();
        FileSemanticVectorStore narrow = new FileSemanticVectorStore(root, NARROW);

        for (String key : List.of("doc-000", "doc-250", "doc-499")) {
            SemanticVectorStore.IndexedDocument expected = loaded.documents().stream()
                    .filter(value -> value.document().stableKey().equals(key)).findFirst().orElseThrow();
            SemanticVectorStore.StoredDocument read = narrow.storedDocument(PROJECT, key).orElseThrow();
            assertEquals(expected, read.document().orElseThrow());
            assertEquals(wide.metadata(PROJECT).orElseThrow(), read.metadata());
        }
        for (String absent : List.of("doc", "doc-0005", "doc-2501", "doc-500", "zzz")) {
            SemanticVectorStore.StoredDocument read = narrow.storedDocument(PROJECT, absent).orElseThrow();
            assertEquals(Optional.empty(), read.document(), absent);
            assertEquals(DOCUMENTS, read.metadata().documentCount());
        }
        assertEquals(0L, narrow.cacheStats().misses(), "no detail decodes the whole index");
        assertEquals(0, narrow.cacheStats().entries());
        assertThrows(IllegalArgumentException.class, () -> narrow.storedDocument(PROJECT, " "));
        assertEquals(Optional.empty(), narrow.storedDocument("project-absent", "doc-000"));
    }

    @Test
    void aStoredDocumentOfAnIndexTheCacheHoldsIsReadFromTheCacheWithoutWalkingTheFile() throws Exception {
        Path root = temp.resolve("semantic");
        FileSemanticVectorStore store = indexOf(root, DOCUMENTS);
        SemanticVectorStore.IndexSnapshot loaded = store.load(PROJECT).orElseThrow();
        SemanticVectorStore.IndexedDocument expected = loaded.documents().stream()
                .filter(value -> value.document().stableKey().equals("doc-321")).findFirst().orElseThrow();

        SemanticVectorStore.StoredDocument read = store.storedDocument(PROJECT, "doc-321").orElseThrow();
        SemanticVectorStore.StoredDocument unknown = store.storedDocument(PROJECT, "doc-999").orElseThrow();

        assertEquals(expected, read.document().orElseThrow());
        assertEquals(store.metadata(PROJECT).orElseThrow(), read.metadata());
        assertEquals(Optional.empty(), unknown.document());
        assertEquals(1L, store.cacheStats().misses(), "the index was decoded once, by the explicit load");
        assertEquals(2L, store.cacheStats().hits(), "both details were served by the cached index");
    }

    @Test
    void anIndexInTheLegacyFormatIsStillReadByTheFullLoadForPagesAndDocuments() throws Exception {
        Path root = temp.resolve("legacy");
        Files.createDirectories(root.resolve(PROJECT));
        SemanticVectorStore.IndexSnapshot legacy = new SemanticVectorStore.IndexSnapshot(PROJECT, "snap-legacy",
                "test-provider", "test-model", SemanticDocumentReadsContract.DIMENSIONS, 3L,
                List.of(SemanticDocumentReadsContract.indexed(PROJECT, "snap-legacy", "doc-a", 0.25, -0.5, 1.0)));
        FileSemanticVectorStoreTest.writeLegacyV1(root.resolve(PROJECT).resolve("index-v1.bin"), legacy);
        FileSemanticVectorStore store = new FileSemanticVectorStore(root);

        SemanticVectorStore.DocumentPage page = store.documentPage(PROJECT, null, 10).orElseThrow();
        SemanticVectorStore.StoredDocument stored = store.storedDocument(PROJECT, "doc-a").orElseThrow();

        assertEquals(List.of("doc-a"), page.documents().stream().map(SemanticDocument::stableKey).toList());
        assertEquals(List.of(0.25, -0.5, 1.0), stored.document().orElseThrow().vector().values());
        assertEquals(1L, store.cacheStats().misses(), "the legacy format is read through one full load");
    }

    @Test
    void theNeighboursOfAnIndexTheCacheCannotHoldAreTheOnesOfTheLoadedIndexWithoutRetainingIt() throws Exception {
        Path root = temp.resolve("semantic");
        FileSemanticVectorStore wide = rankedIndexOf(root);
        FileSemanticVectorStore narrow = new FileSemanticVectorStore(root, NARROW);

        for (String origin : List.of("doc-000", "doc-123", "doc-499")) {
            SemanticVector query = wide.storedDocument(PROJECT, origin).orElseThrow().document().orElseThrow().vector();
            List<SemanticVectorStore.VectorHit> expected = wide.search(PROJECT, query, 20, -1.0);
            List<SemanticVectorStore.VectorHit> walked = narrow.search(PROJECT, query, 20, -1.0);
            assertEquals(20, walked.size());
            assertEquals(expected, walked, origin);
        }
        assertEquals(0L, narrow.cacheStats().misses(), "ranking decodes no whole index");
        assertEquals(0, narrow.cacheStats().entries());
        assertEquals(1L, wide.cacheStats().misses(), "the wide store loaded the index once and kept it");
    }

    @Test
    void equalScoresAreRankedByStableKeyAndTheMinimumScoreIsHonoured() throws Exception {
        Path root = temp.resolve("semantic");
        FileSemanticVectorStore wide = rankedIndexOf(root);
        FileSemanticVectorStore narrow = new FileSemanticVectorStore(root, NARROW);
        SemanticVector query = SemanticVector.fromArray("query", new double[] {1.0, 0.0, 0.0});

        List<SemanticVectorStore.VectorHit> tied = narrow.search(PROJECT, query, 20, -1.0);
        List<SemanticVectorStore.VectorHit> strict = narrow.search(PROJECT, query, 1_000, 0.999);

        assertEquals(wide.search(PROJECT, query, 20, -1.0), tied);
        assertTrue(tied.stream().allMatch(hit -> hit.score() == 1.0), "forty documents tie at the top");
        assertEquals(tied.stream().map(hit -> hit.document().stableKey()).sorted().toList(),
                tied.stream().map(hit -> hit.document().stableKey()).toList());
        assertEquals(wide.search(PROJECT, query, 1_000, 0.999), strict);
        assertEquals(40, strict.size());
        assertEquals(List.of(), narrow.search(PROJECT, SemanticVector.fromArray("q", new double[] {-1.0, 0.0, 0.0}),
                5, 0.999));
    }

    @Test
    void aQueryOfOtherDimensionsIsRefusedWithoutAnyPartialResult() throws Exception {
        Path root = temp.resolve("semantic");
        rankedIndexOf(root);
        FileSemanticVectorStore narrow = new FileSemanticVectorStore(root, NARROW);
        SemanticVector twoDimensions = SemanticVector.fromArray("q", new double[] {1.0, 0.0});

        assertThrows(IllegalArgumentException.class, () -> narrow.search(PROJECT, twoDimensions, 20, -1.0));
        assertThrows(IllegalArgumentException.class, () -> narrow.search(PROJECT,
                SemanticVector.fromArray("q", new double[] {1.0, 0.0, 0.0}), 0, -1.0));
        assertThrows(IllegalArgumentException.class, () -> narrow.search(PROJECT,
                SemanticVector.fromArray("q", new double[] {1.0, 0.0, 0.0}), 20, 2.0));
        assertEquals(List.of(), narrow.search("project-absent",
                SemanticVector.fromArray("q", new double[] {1.0, 0.0, 0.0}), 20, -1.0));
        assertEquals(0L, narrow.cacheStats().misses());
    }

    @Test
    void anIndexTheCacheRefusedIsRankedWithoutBeingDecodedAgain() throws Exception {
        Path root = temp.resolve("semantic");
        FileSemanticVectorStore measuring = indexOf(root, 4);
        measuring.load(PROJECT).orElseThrow();
        long decodedWeight = measuring.cacheStats().weightBytes();
        // The ceiling is under the weight of the decoded index but over the lower bound the walk computes from
        // the header alone: the decision to walk comes from the refusal that the first full load recorded.
        FileSemanticVectorStore store = new FileSemanticVectorStore(root, decodedWeight - 1L);
        store.load(PROJECT).orElseThrow();
        assertEquals(1L, store.cacheStats().misses());
        assertEquals(0, store.cacheStats().entries(), "the decoded index weighs more than the ceiling");
        SemanticVector query = SemanticVector.fromArray("q", new double[] {1.0, 0.0, 0.0});

        List<SemanticVectorStore.VectorHit> hits = store.search(PROJECT, query, 3, -1.0);

        assertEquals(3, hits.size());
        assertEquals(1L, store.cacheStats().misses(), "ranking an index the cache refused decodes nothing");
    }

    @RepeatedTest(50)
    void concurrentColdLoadsOfAnIndexTheCacheHoldsDecodeItOnce() throws Exception {
        Path root = temp.resolve("semantic");
        indexOf(root, 300);
        FileSemanticVectorStore store = new FileSemanticVectorStore(root);

        AtomicReferenceArray<Object> results = concurrently(4, () -> store.load(PROJECT).orElseThrow());

        assertEquals(1L, store.cacheStats().misses(), "four readers decode the index once");
        for (int index = 1; index < 4; index++) assertSame(results.get(0), results.get(index));
        assertEquals(1, store.cacheStats().entries());
    }

    @RepeatedTest(50)
    void concurrentLoadsOfATruncatedIndexAllFailAndLeaveNothingInTheCache() throws Exception {
        Path root = temp.resolve("semantic");
        indexOf(root, 20);
        Path file = root.resolve(PROJECT).resolve("index-v2.bin");
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(bytes, bytes.length - 7));
        FileSemanticVectorStore store = new FileSemanticVectorStore(root);

        AtomicReferenceArray<Object> results = concurrently(2, () -> store.load(PROJECT));

        for (int index = 0; index < 2; index++) {
            assertEquals("truncated semantic index: index-v2.bin", ((IOException) results.get(index)).getMessage());
        }
        assertEquals(0, store.cacheStats().entries());
    }

    @Test
    void concurrentFullLoadsOfAnIndexTheCacheRefusesStillDecodeOncePerCaller() throws Exception {
        Path root = temp.resolve("semantic");
        indexOf(root, 300);
        FileSemanticVectorStore store = new FileSemanticVectorStore(root, NARROW);

        concurrently(4, () -> store.load(PROJECT).orElseThrow());

        assertEquals(4L, store.cacheStats().misses(), "the lock serialises these decodes, it does not remove them");
        assertEquals(0, store.cacheStats().entries());
    }

    @FunctionalInterface
    private interface Call {
        Object run() throws Exception;
    }

    /** Runs {@code call} on {@code threads} threads released together; each slot holds its result or its failure. */
    private static AtomicReferenceArray<Object> concurrently(int threads, Call call) throws Exception {
        AtomicReferenceArray<Object> results = new AtomicReferenceArray<>(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Thread> started = new ArrayList<>();
        for (int index = 0; index < threads; index++) {
            int slot = index;
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    startSignal.await();
                    results.set(slot, call.run());
                } catch (Exception failure) {
                    results.set(slot, failure);
                }
            }, "reader-" + index);
            started.add(thread);
            thread.start();
        }
        assertTrue(ready.await(30, TimeUnit.SECONDS));
        startSignal.countDown();
        for (Thread thread : started) {
            thread.join(TimeUnit.SECONDS.toMillis(60));
            assertFalse(thread.isAlive(), "a reader did not finish");
        }
        return results;
    }

    private static FileSemanticVectorStore indexOf(Path root, int count) throws IOException {
        FileSemanticVectorStore store = new FileSemanticVectorStore(root);
        store.replace(SemanticDocumentReadsContract.snapshot(PROJECT, "snap-1", 5L, documents("snap-1", count)));
        return store;
    }

    private static List<SemanticVectorStore.IndexedDocument> documents(String snapshotId, int count) {
        List<SemanticVectorStore.IndexedDocument> documents = new ArrayList<>(count);
        for (int index = count - 1; index >= 0; index--) {
            documents.add(SemanticDocumentReadsContract.indexed(PROJECT, snapshotId,
                    String.format(Locale.ROOT, "doc-%03d", index), index, 1.0, -1.0));
        }
        return documents;
    }

    /** Five hundred documents: forty share the vector (1, 0, 0), the others are drawn from a fixed seed. */
    private static FileSemanticVectorStore rankedIndexOf(Path root) throws IOException {
        SplittableRandom random = new SplittableRandom(7L);
        List<SemanticVectorStore.IndexedDocument> documents = new ArrayList<>(DOCUMENTS);
        for (int index = 0; index < DOCUMENTS; index++) {
            String key = String.format(Locale.ROOT, "doc-%03d", index);
            if (index % 12 == 0 && index / 12 < 40) {
                documents.add(SemanticDocumentReadsContract.indexed(PROJECT, "snap-1", key, 1.0, 0.0, 0.0));
            } else {
                documents.add(SemanticDocumentReadsContract.indexed(PROJECT, "snap-1", key,
                        random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0)));
            }
        }
        FileSemanticVectorStore store = new FileSemanticVectorStore(root);
        store.replace(SemanticDocumentReadsContract.snapshot(PROJECT, "snap-1", 5L, documents));
        return store;
    }

    private static int indexOf(byte[] bytes, byte[] pattern) {
        for (int at = 0; at <= bytes.length - pattern.length; at++) {
            if (Arrays.equals(bytes, at, at + pattern.length, pattern, 0, pattern.length)) return at;
        }
        throw new AssertionError("pattern not found");
    }
}
