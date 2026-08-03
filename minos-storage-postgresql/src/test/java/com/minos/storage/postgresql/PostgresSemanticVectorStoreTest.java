package com.minos.storage.postgresql;

import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresSemanticVectorStoreTest extends PostgresTestSupport {

    private static final int DIMS = 3;

    @Test
    void replacesAndLoadsIndex() throws Exception {
        String projectId = UUID.randomUUID().toString();
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);
        SemanticVectorStore.IndexSnapshot snapshot = indexSnapshot(projectId, "snap-1",
                indexed("doc-1", 1.0, 0.0, 0.0));

        store.replace(snapshot);
        Optional<SemanticVectorStore.IndexSnapshot> loaded = store.load(projectId);

        assertTrue(loaded.isPresent());
        assertEquals("snap-1", loaded.get().snapshotId());
        assertEquals(1, loaded.get().documents().size());
        assertEquals("doc-1", loaded.get().documents().getFirst().document().stableKey());
    }

    @Test
    void searchRanksByCosineSimilarity() throws Exception {
        String projectId = UUID.randomUUID().toString();
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);

        store.replace(indexSnapshot(projectId, "snap-1",
                indexed("doc-a", 1.0, 0.0, 0.0),
                indexed("doc-b", 0.0, 1.0, 0.0),
                indexed("doc-c", 0.9, 0.1, 0.0)));

        SemanticVector query = SemanticVector.fromArray("q", new double[]{1.0, 0.0, 0.0});
        List<SemanticVectorStore.VectorHit> hits = store.search(projectId, query, 3, 0.0);

        assertEquals(3, hits.size());
        assertEquals("doc-a", hits.get(0).document().stableKey());
        assertEquals("doc-c", hits.get(1).document().stableKey());
        assertEquals("doc-b", hits.get(2).document().stableKey());
        assertTrue(hits.get(0).score() > hits.get(1).score(),
                "doc-a must score higher than doc-c");
        assertTrue(hits.get(1).score() > hits.get(2).score(),
                "doc-c must score higher than doc-b");
    }

    @Test
    void minimumScoreFilterExcludesLowSimilarity() throws Exception {
        String projectId = UUID.randomUUID().toString();
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);

        store.replace(indexSnapshot(projectId, "snap-1",
                indexed("doc-a", 1.0, 0.0, 0.0),
                indexed("doc-b", 0.0, 1.0, 0.0)));

        SemanticVector query = SemanticVector.fromArray("q", new double[]{1.0, 0.0, 0.0});
        List<SemanticVectorStore.VectorHit> hits = store.search(projectId, query, 10, 0.5);

        assertEquals(1, hits.size());
        assertEquals("doc-a", hits.getFirst().document().stableKey());
    }

    @Test
    void isolatesResultsByProjectId() throws Exception {
        String projectA = UUID.randomUUID().toString();
        String projectB = UUID.randomUUID().toString();
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);

        store.replace(indexSnapshot(projectA, "snap-a", indexed("doc-a", 1.0, 0.0, 0.0)));
        store.replace(indexSnapshot(projectB, "snap-b", indexed("doc-b", 0.0, 1.0, 0.0)));

        SemanticVector query = SemanticVector.fromArray("q", new double[]{1.0, 0.0, 0.0});
        List<SemanticVectorStore.VectorHit> hitsA = store.search(projectA, query, 10, 0.0);
        List<SemanticVectorStore.VectorHit> hitsB = store.search(projectB, query, 10, 0.0);

        assertEquals(1, hitsA.size());
        assertEquals(1, hitsB.size());
        assertEquals("doc-a", hitsA.getFirst().document().stableKey());
        assertEquals("doc-b", hitsB.getFirst().document().stableKey());
    }

    @Test
    void replaceIsAtomicAndRemovesPreviousDocuments() throws Exception {
        String projectId = UUID.randomUUID().toString();
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);

        store.replace(indexSnapshot(projectId, "snap-1",
                indexed("old-doc", 1.0, 0.0, 0.0)));
        store.replace(indexSnapshot(projectId, "snap-2",
                indexed("new-doc", 0.0, 1.0, 0.0)));

        Optional<SemanticVectorStore.IndexSnapshot> loaded = store.load(projectId);
        assertTrue(loaded.isPresent());
        assertEquals("snap-2", loaded.get().snapshotId());
        assertEquals(1, loaded.get().documents().size());
        assertEquals("new-doc", loaded.get().documents().getFirst().document().stableKey());
    }

    @Test
    void deleteRemovesProjectDocumentsAndMeta() throws Exception {
        String projectId = UUID.randomUUID().toString();
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);
        store.replace(indexSnapshot(projectId, "snap-1", indexed("doc-1", 1.0, 0.0, 0.0)));

        store.delete(projectId);

        assertTrue(store.load(projectId).isEmpty());
    }

    @Test
    void returnsEmptyForUnknownProject() throws Exception {
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);
        assertTrue(store.load(UUID.randomUUID().toString()).isEmpty());
    }

    @SafeVarargs
    private static SemanticVectorStore.IndexSnapshot indexSnapshot(
            String projectId, String snapshotId,
            SemanticVectorStore.IndexedDocument... documents) {
        return new SemanticVectorStore.IndexSnapshot(
                projectId, snapshotId, "test-provider", "nomic-embed-test", DIMS, 1L,
                List.of(documents));
    }

    private static SemanticVectorStore.IndexedDocument indexed(String stableKey, double d0, double d1, double d2) {
        SemanticDocument document = new SemanticDocument(
                stableKey + "-id", stableKey, "project-unused", "snap-1",
                SemanticDocumentKind.SYMBOL, "Service.java", "Service.java",
                1, 10, "public void " + stableKey + "()", "chk-" + stableKey);
        SemanticVector vector = SemanticVector.fromArray(stableKey, new double[]{d0, d1, d2});
        return new SemanticVectorStore.IndexedDocument(document, vector);
    }
}
