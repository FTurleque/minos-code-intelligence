package com.minos.store;

import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSemanticVectorStoreTest {

    @Test
    void roundTripsCachesAndAtomicallyReplacesReconstructibleIndex(@TempDir Path temp) throws Exception {
        FileSemanticVectorStore store = new FileSemanticVectorStore(temp.resolve("semantic"));
        SemanticVectorStore.IndexSnapshot first = index("project-1", "snapshot-1", "model-v1", "alpha", List.of(1.0, 0.0));

        store.replace(first);
        SemanticVectorStore.IndexSnapshot loaded = store.load("project-1").orElseThrow();
        assertEquals("snapshot-1", loaded.snapshotId());
        assertEquals("provider", loaded.providerId());
        assertEquals("model-v1", loaded.modelId());
        assertEquals(2, loaded.dimensions());
        assertEquals("symbol:alpha", loaded.documents().getFirst().document().stableKey());
        assertEquals(List.of(1.0, 0.0), loaded.documents().getFirst().vector().values());
        assertEquals(1.0, loaded.documents().getFirst().vector().valueAt(0));
        assertEquals(1.0, loaded.documents().getFirst().vector().norm());
        assertSame(loaded, store.load("project-1").orElseThrow());
        assertTrue(store.sizeBytes("project-1") > 0);

        FileSemanticVectorStore reopened = new FileSemanticVectorStore(store.root());
        SemanticVectorStore.IndexSnapshot reconstructed = reopened.load("project-1").orElseThrow();
        assertEquals(loaded.snapshotId(), reconstructed.snapshotId());
        assertEquals(loaded.documents(), reconstructed.documents());

        SemanticVectorStore.IndexSnapshot second = index("project-1", "snapshot-2", "model-v1", "beta", List.of(0.0, 1.0));
        store.replace(second);
        SemanticVectorStore.IndexSnapshot replaced = store.load("project-1").orElseThrow();
        assertEquals("snapshot-2", replaced.snapshotId());
        assertEquals("symbol:beta", replaced.documents().getFirst().document().stableKey());

        store.delete("project-1");
        assertFalse(store.load("project-1").isPresent());
    }

    private static SemanticVectorStore.IndexSnapshot index(
            String projectId,
            String snapshotId,
            String modelId,
            String suffix,
            List<Double> vector
    ) {
        String stableKey = "symbol:" + suffix;
        SemanticDocument document = new SemanticDocument(
                "semantic:" + snapshotId + ":" + suffix,
                stableKey,
                projectId,
                snapshotId,
                SemanticDocumentKind.SYMBOL,
                suffix,
                "src/Fixture.java",
                1,
                2,
                "content " + suffix,
                "checksum-" + suffix);
        return new SemanticVectorStore.IndexSnapshot(
                projectId,
                snapshotId,
                "provider",
                modelId,
                vector.size(),
                1234L,
                List.of(new SemanticVectorStore.IndexedDocument(document, new SemanticVector(stableKey, vector))));
    }
}
