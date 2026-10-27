package com.minos.storage.local.store;

import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore;
import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The identifier of the active snapshot is answered by the active pointer: the snapshot is neither read
 * nor decoded. The index documents can likewise be read without going through the full index.
 */
class ActiveSnapshotIdTest {

    @Test
    void aProjectWithoutActiveSnapshotHasNoActiveIdentifier(@TempDir Path root) throws Exception {
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root);
        UUID projectId = UUID.randomUUID();

        assertTrue(store.activeSnapshotId(projectId).isEmpty());
        assertEquals(store.loadActiveKnowledge(projectId).map(CodeKnowledgeSnapshot::snapshotId),
                store.activeSnapshotId(projectId));
    }

    @Test
    void theIdentifierIsThatOfTheLoadedSnapshot(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = publish(root, projectId, "snapshot-1");
        publish(root, projectId, "snapshot-2");

        assertEquals(Optional.of("snapshot-2"), store.activeSnapshotId(projectId));
        assertEquals(store.loadActiveKnowledge(projectId).map(CodeKnowledgeSnapshot::snapshotId),
                store.activeSnapshotId(projectId));
    }

    @Test
    void theIdentifierIsReadWithoutOpeningTheSnapshotFile(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = publish(root, projectId, "snapshot-1");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path snapshot : files.filter(file -> file.getFileName().toString().endsWith(".knowledge")).toList()) {
                Files.delete(snapshot);
            }
        }

        assertEquals(Optional.of("snapshot-1"), store.activeSnapshotId(projectId));
        assertEquals(0, store.cacheStats().fullSnapshotLoads());
    }

    @Test
    void theMutationGuardReadsDocumentsFromItsDelegateWithoutLoadingTheIndex(@TempDir Path root) throws Exception {
        String projectId = UUID.randomUUID().toString();
        CountingStore delegate = new CountingStore(projectId);
        SemanticVectorStore guarded = new ProjectMutationSemanticVectorStore(root, delegate);

        SemanticVectorStore.IndexDocuments documents = guarded.documents(projectId).orElseThrow();

        assertEquals(List.of("symbol:alpha"), documents.documents().stream().map(SemanticDocument::stableKey).toList());
        assertEquals("snapshot-1", documents.metadata().snapshotId());
        assertEquals(0, delegate.loads);
    }

    @Test
    void aStoreWithoutItsOwnDocumentReadDerivesItFromTheIndex() throws Exception {
        String projectId = UUID.randomUUID().toString();
        SemanticVectorStore.IndexSnapshot index = index(projectId);
        SemanticVectorStore store = new SemanticVectorStore() {
            @Override
            public Optional<IndexSnapshot> load(String ignored) {
                return Optional.of(index);
            }

            @Override
            public void replace(IndexSnapshot snapshot) {
                throw new UnsupportedOperationException("not written by this test");
            }

            @Override
            public void delete(String ignored) {
                throw new UnsupportedOperationException("not written by this test");
            }

            @Override
            public void replaceConditionally(IndexSnapshot next, String expected, ActiveSnapshotIdReader reader) {
                throw new UnsupportedOperationException("not written by this test");
            }
        };

        SemanticVectorStore.IndexDocuments documents = store.documents(projectId).orElseThrow();

        assertEquals(store.metadata(projectId).orElseThrow(), documents.metadata());
        assertEquals(index.documents().stream().map(SemanticVectorStore.IndexedDocument::document).toList(),
                documents.documents());
    }

    private static FileSymbolSnapshotStore publish(Path root, UUID projectId, String snapshotId) throws IOException {
        CodeKnowledgeSnapshot snapshot = QueryViewWeightFixtures.repository(projectId, snapshotId, 1,
                List.<String[]>of(QueryViewWeightFixtures.ASCII));
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root);
        store.publish(projectId, snapshotId, snapshot.symbols(), snapshot.occurrences(), snapshot.relationships());
        return store;
    }

    private static SemanticVectorStore.IndexSnapshot index(String projectId) {
        SemanticDocument document = new SemanticDocument("doc-alpha", "symbol:alpha", projectId, "snapshot-1",
                SemanticDocumentKind.SYMBOL, "sym-alpha", "src/Alpha.java", 1, 2, "method alpha", "checksum-alpha");
        return new SemanticVectorStore.IndexSnapshot(projectId, "snapshot-1", "fake", "model", 2, 1_000L,
                List.of(new SemanticVectorStore.IndexedDocument(document,
                        SemanticVector.fromArray("symbol:alpha", new double[] {1.0, 0.0}))));
    }

    /** Answers documents on its own and counts the full loads it should not receive. */
    private static final class CountingStore implements SemanticVectorStore {
        private final IndexSnapshot index;
        private int loads;

        CountingStore(String projectId) {
            this.index = index(projectId);
        }

        @Override
        public Optional<IndexSnapshot> load(String projectId) {
            loads++;
            return Optional.of(index);
        }

        @Override
        public Optional<IndexDocuments> documents(String projectId) {
            return Optional.of(new IndexDocuments(
                    new IndexMetadata(index.projectId(), index.snapshotId(), index.providerId(), index.modelId(),
                            index.dimensions(), index.builtAtEpochMilli(), index.documents().size()),
                    index.documents().stream().map(IndexedDocument::document).toList()));
        }

        @Override
        public void replace(IndexSnapshot snapshot) {
            throw new UnsupportedOperationException("not written by this test");
        }

        @Override
        public void delete(String projectId) {
            throw new UnsupportedOperationException("not written by this test");
        }

        @Override
        public void replaceConditionally(IndexSnapshot next, String expected, ActiveSnapshotIdReader reader) {
            throw new UnsupportedOperationException("not written by this test");
        }
    }
}
