package com.minos.storage.postgresql;

import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore;
import com.minos.semantic.StaleSemanticSyncException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresSemanticSyncConsistencyTest extends PostgresTestSupport {

    @TempDir Path tempDir;

    @Test
    void conditionalCommitUsesAuthoritativeReaderWithoutBorrowingASecondLease() throws Exception {
        UUID projectId = UUID.randomUUID();
        String text = projectId.toString();
        PostgresCodeKnowledgeSnapshotStore knowledge = new PostgresCodeKnowledgeSnapshotStore(connections, tempDir);
        PostgresSemanticVectorStore semantic = new PostgresSemanticVectorStore(connections);
        knowledge.publish(projectId, "snap-1", List.of(), List.of(), List.of());
        AtomicBoolean externalReaderCalled = new AtomicBoolean();

        semantic.replaceConditionally(snap(text, "snap-1"), "snap-1", () -> {
            externalReaderCalled.set(true);
            assertEquals(1, connections.poolStats().leased(), "nested reader must reuse the transaction lease");
            return knowledge.loadActiveKnowledge(projectId).map(value -> value.snapshotId());
        });

        assertTrue(externalReaderCalled.get());
        assertEquals(0, connections.poolStats().acquisitionTimeouts());
        assertEquals("snap-1", semantic.metadata(text).orElseThrow().snapshotId());
    }

    @Test
    void conditionalCommitRejectsAStaleStructuralSnapshot() throws Exception {
        UUID projectId = UUID.randomUUID();
        String text = projectId.toString();
        PostgresCodeKnowledgeSnapshotStore knowledge = new PostgresCodeKnowledgeSnapshotStore(connections, tempDir);
        PostgresSemanticVectorStore semantic = new PostgresSemanticVectorStore(connections);
        knowledge.publish(projectId, "snap-2", List.of(), List.of(), List.of());

        assertThrows(StaleSemanticSyncException.class,
                () -> semantic.replaceConditionally(snap(text, "snap-1"), "snap-1",
                        () -> knowledge.loadActiveKnowledge(projectId).map(value -> value.snapshotId())));
        assertTrue(semantic.metadata(text).isEmpty());
    }

    @Test
    void rawSemanticMutationsCannotBypassTheProjectMutationLock() throws Exception {
        UUID projectId = UUID.randomUUID();
        String text = projectId.toString();
        PostgresSemanticVectorStore semantic = new PostgresSemanticVectorStore(connections);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean replaceDone = new AtomicBoolean();

        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> {
                try {
                    connections.inTransaction(connection -> {
                        PostgresProjectMutationLock.acquire(connection, projectId);
                        lockHeld.countDown();
                        try { release.await(10, TimeUnit.SECONDS); }
                        catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new java.io.IOException("test interrupted", exception);
                        }
                        return null;
                    });
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
            assertTrue(lockHeld.await(10, TimeUnit.SECONDS));
            var writer = executor.submit(() -> {
                try { semantic.replace(snap(text, "snap-1")); replaceDone.set(true); }
                catch (Exception exception) { throw new RuntimeException(exception); }
            });
            Thread.sleep(200);
            assertFalse(replaceDone.get(), "raw replace must wait for the shared project mutation lock");
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            writer.get(10, TimeUnit.SECONDS);
        }
        assertTrue(replaceDone.get());
    }

    @Test
    void readsIgnoreDocumentsThatDoNotMatchSemanticMetadataSnapshot() throws Exception {
        UUID projectId = UUID.randomUUID();
        String text = projectId.toString();
        PostgresSemanticVectorStore semantic = new PostgresSemanticVectorStore(connections);
        semantic.replace(snap(text, "snap-1"));
        connections.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                    "INSERT INTO semantic_documents(project_id,stable_key,document_id,snapshot_id,kind,source_id,"
                            + "file_id,start_line,end_line,content,checksum,embedding) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,CAST(? AS vector))")) {
                statement.setObject(1, projectId);
                statement.setString(2, "stray-key");
                statement.setString(3, "stray-id");
                statement.setString(4, "snap-other");
                statement.setString(5, SemanticDocumentKind.SYMBOL.name());
                statement.setString(6, "fixture");
                statement.setString(7, "Stray.java");
                statement.setInt(8, 1); statement.setInt(9, 1);
                statement.setString(10, "stray"); statement.setString(11, "stray-cs");
                statement.setString(12, "[1,0,0]");
                statement.executeUpdate();
            }
            return null;
        });

        SemanticVectorStore.IndexSnapshot loaded = semantic.load(text).orElseThrow();
        assertEquals("snap-1", loaded.snapshotId());
        assertEquals(1, loaded.documents().size());
        assertEquals("snap-1", loaded.documents().getFirst().document().snapshotId());
        List<SemanticVectorStore.VectorHit> hits = semantic.search(
                text, SemanticVector.fromArray("query", new double[]{1, 0, 0}), 20, -1.0);
        assertTrue(hits.stream().allMatch(hit -> "snap-1".equals(hit.document().snapshotId())));
    }

    private static SemanticVectorStore.IndexSnapshot snap(String projectId, String snapshotId) {
        String stableKey = "symbol:pg:" + snapshotId;
        SemanticDocument document = new SemanticDocument("id-" + snapshotId, stableKey, projectId, snapshotId,
                SemanticDocumentKind.SYMBOL, "pg-fixture", "src/Fixture.java", 1, 1,
                "pg content " + snapshotId, "cs-" + snapshotId);
        return new SemanticVectorStore.IndexSnapshot(projectId, snapshotId, "provider", "model-v1", 3,
                System.currentTimeMillis(), List.of(new SemanticVectorStore.IndexedDocument(
                        document, SemanticVector.fromArray(stableKey, new double[]{1, 0, 0}))));
    }
}
