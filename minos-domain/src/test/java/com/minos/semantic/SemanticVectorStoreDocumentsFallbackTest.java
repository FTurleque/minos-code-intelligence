package com.minos.semantic;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The default reading of the documents of an index, for a store that only knows how to load the whole
 * index: documents and metadata come from one load of the same index, the vectors are left behind, and a
 * project without an index has no documents.
 */
class SemanticVectorStoreDocumentsFallbackTest {

    private static final String PROJECT = "project-1";
    private static final String SNAPSHOT = "snapshot-1";

    @Test
    void theDocumentsOfAStoreThatOnlyLoadsAreThoseOfItsIndexInIndexOrder() throws Exception {
        LoadOnlyStore store = new LoadOnlyStore(index(indexed("alpha"), indexed("beta")));

        SemanticVectorStore.IndexDocuments read = store.documents(PROJECT).orElseThrow();

        assertEquals(List.of(document("alpha"), document("beta")), read.documents());
    }

    @Test
    void theMetadataReadWithTheDocumentsDescribesTheSameIndex() throws Exception {
        LoadOnlyStore store = new LoadOnlyStore(index(indexed("alpha"), indexed("beta")));

        SemanticVectorStore.IndexDocuments read = store.documents(PROJECT).orElseThrow();

        assertEquals(new SemanticVectorStore.IndexMetadata(PROJECT, SNAPSHOT, "provider", "model", 2, 1_000L, 2),
                read.metadata());
        assertEquals(store.metadata(PROJECT).orElseThrow(), read.metadata());
    }

    @Test
    void documentsAndMetadataComeFromASingleLoadOfTheIndex() throws Exception {
        LoadOnlyStore store = new LoadOnlyStore(index(indexed("alpha")));

        store.documents(PROJECT);

        assertEquals(List.of(PROJECT), store.loadedProjects);
    }

    @Test
    void aProjectWithoutAnIndexHasNoDocuments() throws Exception {
        LoadOnlyStore store = new LoadOnlyStore(null);

        assertEquals(Optional.empty(), store.documents(PROJECT));
    }

    @Test
    void anIndexWithoutDocumentsIsReadAsAnEmptyListWithItsMetadata() throws Exception {
        LoadOnlyStore store = new LoadOnlyStore(index());

        SemanticVectorStore.IndexDocuments read = store.documents(PROJECT).orElseThrow();

        assertEquals(List.of(), read.documents());
        assertEquals(0, read.metadata().documentCount());
        assertEquals(SNAPSHOT, read.metadata().snapshotId());
    }

    @Test
    void theDocumentsReadDoNotFollowALaterChangeOfTheListTheyWereBuiltFrom() {
        List<SemanticDocument> source = new ArrayList<>(List.of(document("alpha")));
        SemanticVectorStore.IndexMetadata metadata =
                new SemanticVectorStore.IndexMetadata(PROJECT, SNAPSHOT, "provider", "model", 2, 1_000L, 1);

        SemanticVectorStore.IndexDocuments read = new SemanticVectorStore.IndexDocuments(metadata, source);
        source.add(document("beta"));
        List<SemanticDocument> exposed = read.documents();
        SemanticDocument extra = document("gamma");

        assertEquals(List.of(document("alpha")), exposed);
        assertThrows(UnsupportedOperationException.class, () -> exposed.add(extra));
    }

    @Test
    void documentsWithoutMetadataOrWithoutAListAreRefused() {
        List<SemanticDocument> documents = List.of(document("alpha"));
        SemanticVectorStore.IndexMetadata metadata =
                new SemanticVectorStore.IndexMetadata(PROJECT, SNAPSHOT, "provider", "model", 2, 1_000L, 1);

        NullPointerException noMetadata = assertThrows(NullPointerException.class,
                () -> new SemanticVectorStore.IndexDocuments(null, documents));
        NullPointerException noDocuments = assertThrows(NullPointerException.class,
                () -> new SemanticVectorStore.IndexDocuments(metadata, null));

        assertEquals("metadata", noMetadata.getMessage());
        assertEquals("documents", noDocuments.getMessage());
    }

    private static SemanticVectorStore.IndexSnapshot index(SemanticVectorStore.IndexedDocument... documents) {
        return new SemanticVectorStore.IndexSnapshot(PROJECT, SNAPSHOT, "provider", "model", 2, 1_000L,
                List.of(documents));
    }

    private static SemanticVectorStore.IndexedDocument indexed(String name) {
        return new SemanticVectorStore.IndexedDocument(document(name),
                SemanticVector.fromArray("symbol:" + name, new double[] {1.0, 0.0}));
    }

    private static SemanticDocument document(String name) {
        return new SemanticDocument("doc-" + name, "symbol:" + name, PROJECT, SNAPSHOT, SemanticDocumentKind.SYMBOL,
                "sym-" + name, "src/" + name + ".java", 1, 1, "method " + name, "checksum-" + name);
    }

    /** Implements what the interface requires and nothing else: every other reading is the default one. */
    private static final class LoadOnlyStore implements SemanticVectorStore {
        private final IndexSnapshot index;
        private final List<String> loadedProjects = new ArrayList<>();

        LoadOnlyStore(IndexSnapshot index) {
            this.index = index;
        }

        @Override
        public Optional<IndexSnapshot> load(String projectId) {
            loadedProjects.add(projectId);
            return Optional.ofNullable(index);
        }

        @Override
        public void replace(IndexSnapshot snapshot) {
            throw new UnsupportedOperationException("the index is not written by this test");
        }

        @Override
        public void delete(String projectId) {
            throw new UnsupportedOperationException("the index is not written by this test");
        }

        @Override
        public void replaceConditionally(IndexSnapshot next, String expectedActiveSnapshotId,
                                         ActiveSnapshotIdReader activeSnapshotReader) {
            throw new UnsupportedOperationException("the index is not written by this test");
        }
    }
}
