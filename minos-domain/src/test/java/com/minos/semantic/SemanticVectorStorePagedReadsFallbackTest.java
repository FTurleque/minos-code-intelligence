package com.minos.semantic;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The default paged reading of the documents of an index and the default reading of one stored vector, for a
 * store that only knows how to load the whole index.
 */
class SemanticVectorStorePagedReadsFallbackTest {

    private static final String PROJECT = "project-1";
    private static final String SNAPSHOT = "snapshot-1";

    @Test
    void pagesFollowEachOtherInStableKeyOrderWithoutDuplicate() throws Exception {
        LoadOnlyStore store = new LoadOnlyStore(index(indexed("gamma"), indexed("alpha"), indexed("beta")));

        SemanticVectorStore.DocumentPage first = store.documentPage(PROJECT, null, 2).orElseThrow();
        SemanticVectorStore.DocumentPage second = store.documentPage(PROJECT, "beta", 2).orElseThrow();

        assertEquals(List.of("alpha", "beta"), keys(first));
        assertTrue(first.hasMore());
        assertEquals(List.of("gamma"), keys(second));
        assertFalse(second.hasMore());
        assertEquals(3, first.metadata().documentCount());
        assertEquals(first.metadata(), second.metadata());
    }

    @Test
    void aPageThatEndsExactlyOnTheLastDocumentHasNoFollower() throws Exception {
        LoadOnlyStore store = new LoadOnlyStore(index(indexed("alpha"), indexed("beta")));

        SemanticVectorStore.DocumentPage page = store.documentPage(PROJECT, null, 2).orElseThrow();

        assertEquals(List.of("alpha", "beta"), keys(page));
        assertFalse(page.hasMore());
        assertEquals(List.of(), keys(store.documentPage(PROJECT, "beta", 2).orElseThrow()));
    }

    @Test
    void aPageSizeOutOfBoundsIsRefusedBeforeTheIndexIsRead() {
        LoadOnlyStore store = new LoadOnlyStore(index(indexed("alpha")));

        assertThrows(IllegalArgumentException.class, () -> store.documentPage(PROJECT, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> store.documentPage(PROJECT, null, SemanticVectorStore.MAX_DOCUMENT_PAGE_SIZE + 1));
        assertThrows(IllegalArgumentException.class, () -> store.documentPage(PROJECT, " ", 1));
        assertEquals(List.of(), store.loadedProjects);
    }

    @Test
    void aProjectWithoutAnIndexHasNeitherPageNorDocument() throws Exception {
        LoadOnlyStore store = new LoadOnlyStore(null);

        assertEquals(Optional.empty(), store.documentPage(PROJECT, null, 10));
        assertEquals(Optional.empty(), store.storedDocument(PROJECT, "alpha"));
    }

    @Test
    void theStoredVectorOfADocumentIsReadWithTheMetadataOfItsIndex() throws Exception {
        LoadOnlyStore store = new LoadOnlyStore(index(indexed("alpha"), indexed("beta")));

        SemanticVectorStore.StoredDocument read = store.storedDocument(PROJECT, "beta").orElseThrow();

        assertEquals(List.of(1.0, 0.0), read.document().orElseThrow().vector().values());
        assertEquals("beta", read.document().orElseThrow().vector().stableKey());
        assertEquals("content beta", read.document().orElseThrow().document().content());
        assertEquals(store.metadata(PROJECT).orElseThrow(), read.metadata());
    }

    @Test
    void anUnknownStableKeyHasNoVectorButStillNamesItsIndex() throws Exception {
        LoadOnlyStore store = new LoadOnlyStore(index(indexed("alpha")));

        SemanticVectorStore.StoredDocument read = store.storedDocument(PROJECT, "missing").orElseThrow();

        assertEquals(Optional.empty(), read.document());
        assertEquals(SNAPSHOT, read.metadata().snapshotId());
        assertThrows(IllegalArgumentException.class, () -> store.storedDocument(PROJECT, " "));
    }

    @Test
    void aStoredDocumentWithoutVectorOrOfAnotherDimensionCountThanItsIndexIsRefused() {
        SemanticVectorStore.IndexMetadata metadata =
                new SemanticVectorStore.IndexMetadata(PROJECT, SNAPSHOT, "provider", "model", 3, 1_000L, 1);
        Optional<SemanticVectorStore.IndexedDocument> twoDimensions = Optional.of(indexed("alpha"));
        Optional<SemanticVectorStore.IndexedDocument> withoutVector = Optional.of(
                SemanticVectorStore.IndexedDocument.reusingStoredVector(indexed("alpha").document()));

        assertThrows(IllegalArgumentException.class,
                () -> new SemanticVectorStore.StoredDocument(metadata, twoDimensions));
        assertThrows(IllegalArgumentException.class,
                () -> new SemanticVectorStore.StoredDocument(metadata, withoutVector));
    }

    @Test
    void vectorPagesFollowEachOtherInStableKeyOrderWithTheKindOfEachDocument() throws Exception {
        LoadOnlyStore store = new LoadOnlyStore(index(indexed("gamma"), indexed("alpha"), indexed("beta")));

        SemanticVectorStore.VectorPage first = store.vectorPage(PROJECT, null, 2).orElseThrow();
        SemanticVectorStore.VectorPage second = store.vectorPage(PROJECT, "beta", 2).orElseThrow();

        assertEquals(List.of("alpha", "beta"),
                first.vectors().stream().map(SemanticVectorStore.StoredVector::stableKey).toList());
        assertTrue(first.hasMore());
        assertEquals(List.of("gamma"),
                second.vectors().stream().map(SemanticVectorStore.StoredVector::stableKey).toList());
        assertFalse(second.hasMore());
        assertEquals(SemanticDocumentKind.SYMBOL, first.vectors().getFirst().kind());
        assertEquals(List.of(1.0, 0.0), first.vectors().getFirst().vector().values());
        assertEquals(first.metadata(), second.metadata());
        assertEquals(Optional.empty(), new LoadOnlyStore(null).vectorPage(PROJECT, null, 2));
    }

    @Test
    void aVectorPageOutOfBoundsIsRefusedBeforeTheIndexIsReadAndAPageHoldsVectorsOfItsIndexOnly() {
        LoadOnlyStore store = new LoadOnlyStore(index(indexed("alpha")));
        SemanticVectorStore.IndexMetadata threeDimensions =
                new SemanticVectorStore.IndexMetadata(PROJECT, SNAPSHOT, "provider", "model", 3, 1_000L, 1);
        List<SemanticVectorStore.StoredVector> twoDimensions = List.of(
                new SemanticVectorStore.StoredVector(SemanticDocumentKind.CHUNK, indexed("alpha").vector()));

        assertThrows(IllegalArgumentException.class, () -> store.vectorPage(PROJECT, null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> store.vectorPage(PROJECT, null, SemanticVectorStore.MAX_VECTOR_PAGE_SIZE + 1));
        assertThrows(IllegalArgumentException.class, () -> store.vectorPage(PROJECT, " ", 1));
        assertEquals(List.of(), store.loadedProjects);
        assertThrows(IllegalArgumentException.class,
                () -> new SemanticVectorStore.VectorPage(threeDimensions, twoDimensions, false));
        assertThrows(NullPointerException.class, () -> new SemanticVectorStore.StoredVector(null, indexed("a").vector()));
    }

    private static List<String> keys(SemanticVectorStore.DocumentPage page) {
        return page.documents().stream().map(SemanticDocument::stableKey).toList();
    }

    private static SemanticVectorStore.IndexSnapshot index(SemanticVectorStore.IndexedDocument... documents) {
        return new SemanticVectorStore.IndexSnapshot(PROJECT, SNAPSHOT, "provider", "model", 2, 1_000L, List.of(documents));
    }

    private static SemanticVectorStore.IndexedDocument indexed(String stableKey) {
        SemanticDocument document = new SemanticDocument(stableKey + "-id", stableKey, PROJECT, SNAPSHOT,
                SemanticDocumentKind.SYMBOL, "source", "File.java", 1, 2, "content " + stableKey, "chk-" + stableKey);
        return new SemanticVectorStore.IndexedDocument(document,
                SemanticVector.fromArray(stableKey, new double[]{1.0, 0.0}));
    }

    /** A store that implements the mandatory operations only: every additive reading uses its default. */
    private static final class LoadOnlyStore implements SemanticVectorStore {
        private final IndexSnapshot index;
        private final List<String> loadedProjects = new ArrayList<>();

        private LoadOnlyStore(IndexSnapshot index) {
            this.index = index;
        }

        @Override
        public Optional<IndexSnapshot> load(String projectId) {
            loadedProjects.add(projectId);
            return Optional.ofNullable(index);
        }

        @Override
        public void replace(IndexSnapshot snapshot) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String projectId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void replaceConditionally(IndexSnapshot next, String expectedActiveSnapshotId,
                                         ActiveSnapshotIdReader activeSnapshotReader) {
            throw new UnsupportedOperationException();
        }
    }
}
