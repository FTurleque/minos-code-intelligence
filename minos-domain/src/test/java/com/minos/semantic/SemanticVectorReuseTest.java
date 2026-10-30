package com.minos.semantic;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A document written without its vector keeps the vector the store holds for the same stable key and
 * checksum: the reuse is explicit, resolved only against an index of the same model, and refused when the
 * store holds no such vector. The checksums of an index are read with its metadata.
 */
class SemanticVectorReuseTest {

    private static final String PROJECT = "project-1";

    @Test
    void aDocumentWithoutAVectorMustBeMarkedAsReusingItsStoredVector() {
        SemanticDocument document = document("alpha", "snapshot-2", "checksum-alpha");

        assertThrows(NullPointerException.class, () -> new SemanticVectorStore.IndexedDocument(document, null));
        assertThrows(NullPointerException.class, () -> new SemanticVectorStore.IndexedDocument(document, null, false));
        SemanticVectorStore.IndexedDocument reused = SemanticVectorStore.IndexedDocument.reusingStoredVector(document);
        assertTrue(reused.reusesStoredVector());
        assertNull(reused.vector());
    }

    @Test
    void aDocumentReusingItsStoredVectorCarriesNone() {
        SemanticDocument document = document("alpha", "snapshot-2", "checksum-alpha");
        SemanticVector vector = vector("alpha", 1.0);

        assertThrows(IllegalArgumentException.class,
                () -> new SemanticVectorStore.IndexedDocument(document, vector, true));
    }

    @Test
    void anIndexWithoutReuseIsWrittenAsItIs() throws IOException {
        SemanticVectorStore.IndexSnapshot next = index("snapshot-2", "model",
                embedded("alpha", "snapshot-2", "checksum-alpha", 1.0));

        assertFalse(next.reusesStoredVectors());
        assertSame(next, next.withStoredVectors(Optional.empty()));
    }

    @Test
    void aReusedDocumentTakesTheVectorStoredForItsKeyAndChecksum() throws IOException {
        SemanticVectorStore.IndexSnapshot stored = index("snapshot-1", "model",
                embedded("alpha", "snapshot-1", "checksum-alpha", 0.25),
                embedded("beta", "snapshot-1", "checksum-beta", 0.5));
        SemanticDocument moved = new SemanticDocument("doc-alpha-2", "symbol:alpha", PROJECT, "snapshot-2",
                SemanticDocumentKind.SYMBOL, "sym-alpha", "src/alpha.java", 7, 9, "method alpha", "checksum-alpha");
        SemanticVectorStore.IndexSnapshot next = index("snapshot-2", "model",
                SemanticVectorStore.IndexedDocument.reusingStoredVector(moved),
                embedded("gamma", "snapshot-2", "checksum-gamma", 0.75));

        SemanticVectorStore.IndexSnapshot resolved = next.withStoredVectors(Optional.of(stored));

        assertTrue(next.reusesStoredVectors());
        assertFalse(resolved.reusesStoredVectors());
        assertEquals(moved, resolved.documents().get(0).document());
        assertEquals(stored.documents().get(0).vector(), resolved.documents().get(0).vector());
        assertEquals(next.documents().get(1), resolved.documents().get(1));
        assertEquals("snapshot-2", resolved.snapshotId());
    }

    @Test
    void aReuseThatTheStoreCannotHonourIsRefused() {
        SemanticVectorStore.IndexSnapshot stored = index("snapshot-1", "model",
                embedded("alpha", "snapshot-1", "checksum-alpha", 0.25));
        SemanticVectorStore.IndexSnapshot changedContent = index("snapshot-2", "model",
                SemanticVectorStore.IndexedDocument.reusingStoredVector(document("alpha", "snapshot-2", "other")));
        SemanticVectorStore.IndexSnapshot unknownKey = index("snapshot-2", "model",
                SemanticVectorStore.IndexedDocument.reusingStoredVector(document("delta", "snapshot-2", "checksum-delta")));
        SemanticVectorStore.IndexSnapshot otherModel = index("snapshot-2", "other-model",
                SemanticVectorStore.IndexedDocument.reusingStoredVector(document("alpha", "snapshot-2", "checksum-alpha")));

        assertThrows(IOException.class, () -> changedContent.withStoredVectors(Optional.empty()));
        assertThrows(IOException.class, () -> changedContent.withStoredVectors(Optional.of(stored)));
        assertThrows(IOException.class, () -> unknownKey.withStoredVectors(Optional.of(stored)));
        assertThrows(IOException.class, () -> otherModel.withStoredVectors(Optional.of(stored)));
    }

    @Test
    void theChecksumsOfAStoreThatOnlyLoadsComeWithTheMetadataOfTheSameIndex() throws IOException {
        SemanticVectorStore.IndexSnapshot stored = index("snapshot-1", "model",
                embedded("alpha", "snapshot-1", "checksum-alpha", 0.25),
                embedded("beta", "snapshot-1", "checksum-beta", 0.5));
        LoadOnlyStore store = new LoadOnlyStore(stored);

        SemanticVectorStore.IndexChecksums checksums = store.checksums(PROJECT).orElseThrow();

        assertEquals(Map.of("symbol:alpha", "checksum-alpha", "symbol:beta", "checksum-beta"),
                checksums.checksumsByStableKey());
        assertEquals(store.metadata(PROJECT).orElseThrow(), checksums.metadata());
        assertEquals(Optional.empty(), new LoadOnlyStore(null).checksums(PROJECT));
    }

    @Test
    void checksumsWithoutMetadataOrWithoutAMapAreRefused() {
        SemanticVectorStore.IndexMetadata metadata =
                new SemanticVectorStore.IndexMetadata(PROJECT, "snapshot-1", "provider", "model", 2, 1_000L, 0);
        Map<String, String> none = Map.of();

        assertThrows(NullPointerException.class, () -> new SemanticVectorStore.IndexChecksums(null, none));
        assertThrows(NullPointerException.class, () -> new SemanticVectorStore.IndexChecksums(metadata, null));
    }

    private static SemanticVectorStore.IndexSnapshot index(String snapshot, String model,
                                                           SemanticVectorStore.IndexedDocument... documents) {
        return new SemanticVectorStore.IndexSnapshot(PROJECT, snapshot, "provider", model, 2, 1_000L,
                List.of(documents));
    }

    private static SemanticVectorStore.IndexedDocument embedded(String name, String snapshot, String checksum,
                                                                double value) {
        return new SemanticVectorStore.IndexedDocument(document(name, snapshot, checksum), vector(name, value));
    }

    private static SemanticVector vector(String name, double value) {
        return SemanticVector.fromArray("symbol:" + name, new double[] {1.0, value});
    }

    private static SemanticDocument document(String name, String snapshot, String checksum) {
        return new SemanticDocument("doc-" + name + "-" + snapshot, "symbol:" + name, PROJECT, snapshot,
                SemanticDocumentKind.SYMBOL, "sym-" + name, "src/" + name + ".java", 1, 1, "method " + name, checksum);
    }

    /** Implements what the interface requires and nothing else: every other reading is the default one. */
    private static final class LoadOnlyStore implements SemanticVectorStore {
        private final IndexSnapshot index;

        LoadOnlyStore(IndexSnapshot index) {
            this.index = index;
        }

        @Override
        public Optional<IndexSnapshot> load(String projectId) {
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
