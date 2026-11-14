package com.minos.testsupport;

import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract of the paged reading of the documents of an index and of the reading of one stored vector, the same
 * for every storage backend: each backend runs these tests on its own store.
 */
public interface SemanticDocumentReadsContract {

    int DIMENSIONS = 3;

    /** The store under test. */
    SemanticVectorStore store() throws Exception;

    /** A project identifier the backend accepts, never used before. */
    String newProjectId();

    @Test
    default void aFullWalkReturnsEveryDocumentOnceInStableKeyOrder() throws Exception {
        SemanticVectorStore store = store();
        String projectId = newProjectId();
        List<SemanticVectorStore.IndexedDocument> documents = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        for (int index = 119; index >= 0; index--) {
            String stableKey = String.format(Locale.ROOT, "doc-%03d", index);
            documents.add(indexed(projectId, "snap-1", stableKey, 1.0, index, 0.0));
            expected.add(0, stableKey);
        }
        store.replace(snapshot(projectId, "snap-1", 5L, documents));

        List<String> walked = new ArrayList<>();
        String after = null;
        int pages = 0;
        while (true) {
            SemanticVectorStore.DocumentPage page = store.documentPage(projectId, after, 50).orElseThrow();
            pages++;
            assertEquals(120, page.metadata().documentCount());
            assertEquals("snap-1", page.metadata().snapshotId());
            page.documents().forEach(document -> walked.add(document.stableKey()));
            if (!page.hasMore()) break;
            after = page.documents().getLast().stableKey();
        }

        assertEquals(expected, walked);
        assertEquals(3, pages);
    }

    @Test
    default void aPageCarriesTheWholeDocumentWithoutItsVector() throws Exception {
        SemanticVectorStore store = store();
        String projectId = newProjectId();
        SemanticVectorStore.IndexedDocument stored = indexed(projectId, "snap-1", "doc-a", 1.0, 0.0, 0.0);
        store.replace(snapshot(projectId, "snap-1", 5L, List.of(stored)));

        SemanticVectorStore.DocumentPage page = store.documentPage(projectId, null, 10).orElseThrow();

        assertEquals(List.of(stored.document()), page.documents());
        assertFalse(page.hasMore());
        assertEquals(new SemanticVectorStore.IndexMetadata(
                projectId, "snap-1", "test-provider", "test-model", DIMENSIONS, 5L, 1), page.metadata());
    }

    @Test
    default void aPageSizeOutOfBoundsIsRefused() throws Exception {
        SemanticVectorStore store = store();
        String projectId = newProjectId();
        store.replace(snapshot(projectId, "snap-1", 5L, List.of(indexed(projectId, "snap-1", "doc-a", 1.0, 0.0, 0.0))));

        assertThrows(IllegalArgumentException.class, () -> store.documentPage(projectId, null, 0));
        assertThrows(IllegalArgumentException.class, () -> store.documentPage(projectId, null, 5_000));
    }

    @Test
    default void aPageReadAfterAReplacementNamesTheNewIndexAndMixesNoOldDocument() throws Exception {
        SemanticVectorStore store = store();
        String projectId = newProjectId();
        store.replace(snapshot(projectId, "snap-1", 5L, List.of(
                indexed(projectId, "snap-1", "doc-a", 1.0, 0.0, 0.0),
                indexed(projectId, "snap-1", "doc-b", 0.0, 1.0, 0.0),
                indexed(projectId, "snap-1", "doc-old", 0.0, 0.0, 1.0))));
        SemanticVectorStore.DocumentPage first = store.documentPage(projectId, null, 1).orElseThrow();

        store.replace(snapshot(projectId, "snap-2", 6L, List.of(
                indexed(projectId, "snap-2", "doc-b", 0.0, 1.0, 0.0),
                indexed(projectId, "snap-2", "doc-c", 0.0, 0.0, 1.0))));
        SemanticVectorStore.DocumentPage second = store.documentPage(projectId, "doc-a", 10).orElseThrow();

        assertEquals("snap-1", first.metadata().snapshotId());
        assertEquals("snap-2", second.metadata().snapshotId());
        assertEquals(6L, second.metadata().builtAtEpochMilli());
        assertEquals(2, second.metadata().documentCount());
        assertEquals(List.of("doc-b", "doc-c"), second.documents().stream().map(SemanticDocument::stableKey).toList());
        assertTrue(second.documents().stream().allMatch(document -> "snap-2".equals(document.snapshotId())));
    }

    @Test
    default void aProjectWithoutAnIndexHasNeitherPageNorDocument() throws Exception {
        SemanticVectorStore store = store();
        String projectId = newProjectId();

        assertEquals(Optional.empty(), store.documentPage(projectId, null, 10));
        assertEquals(Optional.empty(), store.storedDocument(projectId, "doc-a"));
    }

    @Test
    default void theStoredVectorOfADocumentIsReadWithTheMetadataOfItsIndex() throws Exception {
        SemanticVectorStore store = store();
        String projectId = newProjectId();
        store.replace(snapshot(projectId, "snap-1", 5L, List.of(
                indexed(projectId, "snap-1", "doc-a", 1.0, 0.0, 0.0),
                indexed(projectId, "snap-1", "doc-b", 0.25, 0.5, -1.0))));

        SemanticVectorStore.StoredDocument read = store.storedDocument(projectId, "doc-b").orElseThrow();
        SemanticVectorStore.StoredDocument unknown = store.storedDocument(projectId, "doc-z").orElseThrow();

        assertEquals(List.of(0.25, 0.5, -1.0), read.document().orElseThrow().vector().values());
        assertEquals("doc-b", read.document().orElseThrow().vector().stableKey());
        assertEquals(indexed(projectId, "snap-1", "doc-b", 0.25, 0.5, -1.0).document(),
                read.document().orElseThrow().document());
        assertEquals(store.metadata(projectId).orElseThrow(), read.metadata());
        assertEquals(Optional.empty(), unknown.document());
        assertEquals("snap-1", unknown.metadata().snapshotId());
    }

    @Test
    default void searchingWithAStoredVectorRanksItsOwnDocumentFirst() throws Exception {
        SemanticVectorStore store = store();
        String projectId = newProjectId();
        store.replace(snapshot(projectId, "snap-1", 5L, List.of(
                indexed(projectId, "snap-1", "doc-a", 1.0, 0.0, 0.0),
                indexed(projectId, "snap-1", "doc-b", 0.0, 1.0, 0.0),
                indexed(projectId, "snap-1", "doc-c", 0.9, 0.1, 0.0))));

        SemanticVector vector = store.storedDocument(projectId, "doc-a").orElseThrow().document().orElseThrow().vector();
        List<SemanticVectorStore.VectorHit> hits = store.search(projectId, vector, 3, -1.0);

        assertEquals(List.of("doc-a", "doc-c", "doc-b"),
                hits.stream().map(hit -> hit.document().stableKey()).toList());
        assertEquals(1.0, hits.getFirst().score(), 1e-6);
    }

    static SemanticVectorStore.IndexSnapshot snapshot(String projectId, String snapshotId, long builtAt,
                                                      List<SemanticVectorStore.IndexedDocument> documents) {
        return new SemanticVectorStore.IndexSnapshot(
                projectId, snapshotId, "test-provider", "test-model", DIMENSIONS, builtAt, documents);
    }

    static SemanticVectorStore.IndexedDocument indexed(String projectId, String snapshotId, String stableKey,
                                                       double first, double second, double third) {
        SemanticDocument document = new SemanticDocument(
                stableKey + "-id", stableKey, projectId, snapshotId,
                SemanticDocumentKind.CHUNK, "symbol-" + stableKey, "src/Service.java",
                1, 10, "public void " + stableKey + "()", "chk-" + stableKey);
        return new SemanticVectorStore.IndexedDocument(
                document, SemanticVector.fromArray(stableKey, new double[]{first, second, third}));
    }
}
