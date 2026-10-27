package com.minos.storage.local.store;

import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The file store rewrites the whole index: a document marked as reused is written with the vector the store
 * holds for its key and checksum, and a reuse it cannot honour leaves the previous index untouched.
 */
class FileSemanticVectorStoreReuseTest {

    @Test
    void aReusedDocumentIsWrittenWithItsStoredVectorAndItsNewPosition(@TempDir Path root) throws Exception {
        SemanticVectorStore store = new ProjectMutationSemanticVectorStore(root, new FileSemanticVectorStore(root));
        String project = UUID.randomUUID().toString();
        store.replace(index(project, "snap-1", new SemanticVectorStore.IndexedDocument(
                document(project, "snap-1", "alpha", 1, "checksum-alpha"), vector("alpha", 0.25))));
        SemanticDocument moved = document(project, "snap-2", "alpha", 5, "checksum-alpha");

        store.replaceConditionally(index(project, "snap-2",
                SemanticVectorStore.IndexedDocument.reusingStoredVector(moved)), "snap-2", () -> Optional.of("snap-2"));

        SemanticVectorStore.IndexSnapshot loaded = store.load(project).orElseThrow();
        assertEquals("snap-2", loaded.snapshotId());
        assertEquals(moved, loaded.documents().getFirst().document());
        assertEquals(0.25, loaded.documents().getFirst().vector().valueAt(1), 1e-6);
        assertEquals(Map.of("symbol:alpha", "checksum-alpha"),
                store.checksums(project).orElseThrow().checksumsByStableKey());
    }

    @Test
    void aReuseTheStoreCannotHonourLeavesThePreviousIndex(@TempDir Path root) throws Exception {
        FileSemanticVectorStore store = new FileSemanticVectorStore(root);
        String project = UUID.randomUUID().toString();
        SemanticVectorStore.IndexSnapshot previous = index(project, "snap-1", new SemanticVectorStore.IndexedDocument(
                document(project, "snap-1", "alpha", 1, "checksum-alpha"), vector("alpha", 0.25)));
        store.replace(previous);
        SemanticVectorStore.IndexSnapshot changed = index(project, "snap-2",
                SemanticVectorStore.IndexedDocument.reusingStoredVector(
                        document(project, "snap-2", "alpha", 1, "another-checksum")));

        assertThrows(IOException.class, () -> store.replace(changed));

        assertEquals(previous.documents().getFirst().document(),
                store.load(project).orElseThrow().documents().getFirst().document());
        assertEquals("snap-1", store.metadata(project).orElseThrow().snapshotId());
    }

    private static SemanticVectorStore.IndexSnapshot index(String project, String snapshot,
                                                           SemanticVectorStore.IndexedDocument... documents) {
        return new SemanticVectorStore.IndexSnapshot(project, snapshot, "provider", "model", 2, 1_000L,
                List.of(documents));
    }

    private static SemanticVector vector(String name, double value) {
        return SemanticVector.fromArray("symbol:" + name, new double[] {1.0, value});
    }

    private static SemanticDocument document(String project, String snapshot, String name, int line, String checksum) {
        return new SemanticDocument("doc-" + name + "-" + snapshot, "symbol:" + name, project, snapshot,
                SemanticDocumentKind.SYMBOL, "sym-" + name, "src/" + name + ".java", line, line + 1,
                "method " + name, checksum);
    }
}
