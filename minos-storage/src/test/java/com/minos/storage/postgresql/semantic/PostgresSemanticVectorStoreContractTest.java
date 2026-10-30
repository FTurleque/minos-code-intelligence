package com.minos.storage.postgresql.semantic;

import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore;
import com.minos.semantic.StaleSemanticSyncException;
import com.minos.storage.postgresql.PostgresTestSupport;
import com.minos.storage.postgresql.support.PostgresConnectionFactory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract of the pgvector store beyond the nominal round trip: metadata and size, argument refusals made
 * before any connection is used, the conditional replacement boundary, and storage failures.
 */
class PostgresSemanticVectorStoreContractTest extends PostgresTestSupport {
    private static final int DIMENSIONS = 3;
    private static final SemanticVector QUERY = SemanticVector.fromArray("query", new double[]{1.0, 0.0, 0.0});

    @Test
    void metadataDescribesTheIndexAndItsDocumentCount() throws Exception {
        String projectId = UUID.randomUUID().toString();
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);
        store.replace(snapshot(projectId, "snap-1", 7L,
                indexed(projectId, "snap-1", "doc-a", 1.0, 0.0, 0.0),
                indexed(projectId, "snap-1", "doc-b", 0.0, 1.0, 0.0)));

        assertEquals(Optional.of(new SemanticVectorStore.IndexMetadata(
                        projectId, "snap-1", "test-provider", "test-model", DIMENSIONS, 7L, 2)),
                store.metadata(projectId));
        assertEquals(Optional.empty(), store.metadata(UUID.randomUUID().toString()));
        assertEquals("pgvector-exact-cosine", store.searchEngine());

        store.delete(projectId);
        assertEquals(Optional.empty(), store.metadata(projectId));
        assertEquals(List.of(), store.search(projectId, QUERY, 10, -1.0));
    }

    @Test
    void anIndexWithoutDocumentsIsStillAnIndex() throws Exception {
        String projectId = UUID.randomUUID().toString();
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);

        store.replace(snapshot(projectId, "snap-empty", 3L));

        SemanticVectorStore.IndexSnapshot loaded = store.load(projectId).orElseThrow();
        assertEquals("snap-empty", loaded.snapshotId());
        assertEquals(List.of(), loaded.documents());
        assertEquals(0, store.metadata(projectId).orElseThrow().documentCount());
        assertEquals(0L, store.sizeBytes(projectId));
        assertEquals(List.of(), store.search(projectId, QUERY, 10, -1.0));
    }

    @Test
    void theDocumentsOfAnIndexWithoutDocumentsAreAnEmptyListWithItsMetadata() throws Exception {
        String projectId = UUID.randomUUID().toString();
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);
        store.replace(snapshot(projectId, "snap-empty", 3L));

        SemanticVectorStore.IndexDocuments read = store.documents(projectId).orElseThrow();

        assertEquals(List.of(), read.documents());
        assertEquals(new SemanticVectorStore.IndexMetadata(
                projectId, "snap-empty", "test-provider", "test-model", DIMENSIONS, 3L, 0), read.metadata());
    }

    @Test
    void theDocumentsReadAreThoseOfTheProjectAndOfItsCurrentIndexOnly() throws Exception {
        String projectId = UUID.randomUUID().toString();
        String otherProjectId = UUID.randomUUID().toString();
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);
        store.replace(snapshot(otherProjectId, "snap-1", 1L, indexed(otherProjectId, "snap-1", "doc-other", 0.0, 0.0, 1.0)));
        store.replace(snapshot(projectId, "snap-1", 1L, indexed(projectId, "snap-1", "doc-old", 1.0, 0.0, 0.0)));
        SemanticVectorStore.IndexedDocument second = indexed(projectId, "snap-2", "doc-b", 0.0, 1.0, 0.0);
        SemanticVectorStore.IndexedDocument first = indexed(projectId, "snap-2", "doc-a", 1.0, 0.0, 0.0);
        store.replace(snapshot(projectId, "snap-2", 2L, second, first));

        SemanticVectorStore.IndexDocuments read = store.documents(projectId).orElseThrow();

        assertEquals(List.of(first.document(), second.document()), read.documents());
        assertEquals(new SemanticVectorStore.IndexMetadata(
                projectId, "snap-2", "test-provider", "test-model", DIMENSIONS, 2L, 2), read.metadata());
        store.delete(projectId);
        assertEquals(Optional.empty(), store.documents(projectId));
    }

    @Test
    void theDocumentsAreReadWithoutParsingTheStoredVectors() throws Exception {
        UUID id = UUID.randomUUID();
        String projectId = id.toString();
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);
        SemanticVectorStore.IndexedDocument stored = indexed(projectId, "snap-1", "doc-a", 1.0, 0.0, 0.0);
        store.replace(snapshot(projectId, "snap-1", 1L, stored));
        connections.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE semantic_index_meta SET dimensions=5 WHERE project_id=?")) {
                statement.setObject(1, id);
                return statement.executeUpdate();
            }
        });

        SemanticVectorStore.IndexDocuments read = store.documents(projectId).orElseThrow();
        IOException fullLoad = assertThrows(IOException.class, () -> store.load(projectId));

        assertEquals(List.of(stored.document()), read.documents());
        assertEquals(5, read.metadata().dimensions());
        assertEquals("pgvector dimensions mismatch", fullLoad.getMessage());
    }

    @Test
    void aFailedReadOfTheDocumentsIsReportedAsAnIoExceptionNamingTheOperation() throws Exception {
        PostgresSemanticVectorStore closed = new PostgresSemanticVectorStore(closedFactory());
        String projectId = UUID.randomUUID().toString();

        IOException storage = assertThrows(IOException.class, () -> closed.documents(projectId));
        IOException invalid = assertThrows(IOException.class, () -> closed.documents("not-a-uuid"));

        assertEquals("unable to load pgvector semantic documents", storage.getMessage());
        assertInstanceOf(SQLException.class, storage.getCause());
        assertEquals("semantic project id must be a UUID", invalid.getMessage());
    }

    @Test
    void aLoadedDocumentAndItsVectorAreTheOnesThatWereStored() throws Exception {
        String projectId = UUID.randomUUID().toString();
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);
        SemanticVectorStore.IndexedDocument stored = indexed(projectId, "snap-1", "doc-a", 0.5, -0.25, 0.75);
        store.replace(snapshot(projectId, "snap-1", 1L, stored));

        SemanticVectorStore.IndexedDocument loaded = store.load(projectId).orElseThrow().documents().getFirst();

        assertEquals(stored.document(), loaded.document());
        assertEquals("doc-a", loaded.vector().stableKey());
        assertEquals(DIMENSIONS, loaded.vector().dimensions());
        for (int index = 0; index < DIMENSIONS; index++) {
            assertEquals(stored.vector().valueAt(index), loaded.vector().valueAt(index), 1.0e-6, "component " + index);
        }
    }

    @Test
    void sizeGrowsWithTheStoredDocumentsAndIsZeroWithoutAnIndex() throws Exception {
        String projectId = UUID.randomUUID().toString();
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);
        assertEquals(0L, store.sizeBytes(projectId));

        store.replace(snapshot(projectId, "snap-1", 1L, indexed(projectId, "snap-1", "doc-a", 1.0, 0.0, 0.0)));
        long one = store.sizeBytes(projectId);
        store.replace(snapshot(projectId, "snap-2", 2L,
                indexed(projectId, "snap-2", "doc-a", 1.0, 0.0, 0.0),
                indexed(projectId, "snap-2", "doc-b", 0.0, 1.0, 0.0)));
        long two = store.sizeBytes(projectId);

        assertTrue(one > 0L, "size " + one);
        assertTrue(two > one, two + " must exceed " + one);
        store.delete(projectId);
        assertEquals(0L, store.sizeBytes(projectId));
    }

    @Test
    void searchHonoursItsLimitAndReturnsScoresInsideTheCosineRange() throws Exception {
        String projectId = UUID.randomUUID().toString();
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);
        store.replace(snapshot(projectId, "snap-1", 1L,
                indexed(projectId, "snap-1", "doc-same", 1.0, 0.0, 0.0),
                indexed(projectId, "snap-1", "doc-orthogonal", 0.0, 1.0, 0.0),
                indexed(projectId, "snap-1", "doc-opposite", -1.0, 0.0, 0.0)));

        List<SemanticVectorStore.VectorHit> best = store.search(projectId, QUERY, 1, -1.0);
        List<SemanticVectorStore.VectorHit> all = store.search(projectId, QUERY, 1_000, -1.0);
        List<SemanticVectorStore.VectorHit> exact = store.search(projectId, QUERY, 10, 1.0);

        assertEquals(List.of("doc-same"), keys(best));
        assertEquals(List.of("doc-same", "doc-orthogonal", "doc-opposite"), keys(all));
        assertEquals(1.0, all.get(0).score(), 1.0e-6);
        assertEquals(0.0, all.get(1).score(), 1.0e-6);
        assertEquals(-1.0, all.get(2).score(), 1.0e-6);
        assertEquals(List.of("doc-same"), keys(exact));
        assertEquals(best.getFirst().document(), all.getFirst().document());
    }

    @Test
    void anIndexLargerThanOneInsertBatchIsStoredEntirely() throws Exception {
        String projectId = UUID.randomUUID().toString();
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);
        List<SemanticVectorStore.IndexedDocument> documents = new ArrayList<>();
        for (int index = 0; index < 501; index++) {
            documents.add(indexed(projectId, "snap-1", String.format("doc-%04d", index), 1.0, index, 0.0));
        }

        store.replace(new SemanticVectorStore.IndexSnapshot(
                projectId, "snap-1", "test-provider", "test-model", DIMENSIONS, 1L, documents));

        assertEquals(501, store.metadata(projectId).orElseThrow().documentCount());
        SemanticVectorStore.IndexSnapshot loaded = store.load(projectId).orElseThrow();
        assertEquals("doc-0000", loaded.documents().getFirst().document().stableKey());
        assertEquals("doc-0500", loaded.documents().getLast().document().stableKey());
    }

    @Test
    void searchArgumentsAreRefusedBeforeAnyConnectionIsUsed() throws Exception {
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(closedFactory());
        String projectId = UUID.randomUUID().toString();

        for (int limit : new int[]{0, -1, 1_001}) {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> store.search(projectId, QUERY, limit, 0.0), "limit " + limit);
            assertEquals("limit must be between 1 and 1000", refusal.getMessage());
        }
        for (double minimum : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.01, 1.01}) {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> store.search(projectId, QUERY, 10, minimum), "minimumScore " + minimum);
            assertEquals("minimumScore must be between -1 and 1", refusal.getMessage());
        }
        assertThrows(NullPointerException.class, () -> store.search(projectId, null, 10, 0.0));
        // The same store fails with an IOException as soon as it needs a connection: the refusals above came first.
        IOException storage = assertThrows(IOException.class, () -> store.search(projectId, QUERY, 1_000, -1.0));
        assertEquals("unable to search pgvector semantic index", storage.getMessage());
    }

    @Test
    void aProjectIdThatIsNotAUuidIsRefusedByEveryOperationBeforeAnyConnectionIsUsed() throws Exception {
        // On a closed pool an operation that reached the database would fail with "unable to ..." instead.
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(closedFactory());
        String invalid = "not-a-uuid";
        SemanticVectorStore.IndexSnapshot snapshot = snapshot(invalid, "snap-1", 1L,
                indexed(invalid, "snap-1", "doc-a", 1.0, 0.0, 0.0));
        AtomicBoolean readerCalled = new AtomicBoolean();
        SemanticVectorStore.ActiveSnapshotIdReader reader = () -> {
            readerCalled.set(true);
            return Optional.of("snap-1");
        };

        List<IOException> refusals = List.of(
                assertThrows(IOException.class, () -> store.metadata(invalid)),
                assertThrows(IOException.class, () -> store.load(invalid)),
                assertThrows(IOException.class, () -> store.delete(invalid)),
                assertThrows(IOException.class, () -> store.sizeBytes(invalid)),
                assertThrows(IOException.class, () -> store.search(invalid, QUERY, 10, 0.0)),
                assertThrows(IOException.class, () -> store.replace(snapshot)),
                assertThrows(IOException.class, () -> store.replaceConditionally(snapshot, "snap-1", reader)));

        for (IOException refusal : refusals) {
            assertEquals("semantic project id must be a UUID", refusal.getMessage());
        }
        assertFalse(readerCalled.get(), "the active snapshot must not be read for an invalid project");
    }

    @Test
    void aConditionalReplacementIsAppliedWhenTheActiveSnapshotIsStillTheExpectedOne() throws Exception {
        UUID id = UUID.randomUUID();
        String projectId = id.toString();
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);
        activateKnowledgeSnapshot(id, "snap-2");
        AtomicInteger leasedWhileReading = new AtomicInteger(-1);
        SemanticVectorStore.ActiveSnapshotIdReader reader = () -> {
            try {
                Optional<String> active = connections.withConnection(
                        connection -> PostgresSemanticReadQueries.activeKnowledgeSnapshotId(connection, id));
                leasedWhileReading.set(connections.poolStats().leased());
                return active;
            } catch (SQLException exception) {
                throw new IOException("active snapshot read failed", exception);
            }
        };

        store.replaceConditionally(
                snapshot(projectId, "snap-2", 2L, indexed(projectId, "snap-2", "doc-new", 0.0, 1.0, 0.0)),
                "snap-2", reader);

        assertEquals("snap-2", store.load(projectId).orElseThrow().snapshotId());
        assertEquals(1, leasedWhileReading.get(), "the reader must reuse the transaction connection, not borrow another");
        assertEquals(Optional.empty(), connections.withConnection(
                connection -> PostgresSemanticReadQueries.activeKnowledgeSnapshotId(connection, UUID.randomUUID())));
    }

    @Test
    void aStaleConditionalReplacementIsRefusedAndThePreviousIndexIsKept() throws Exception {
        String projectId = UUID.randomUUID().toString();
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);
        store.replace(snapshot(projectId, "snap-1", 1L, indexed(projectId, "snap-1", "doc-old", 1.0, 0.0, 0.0)));
        SemanticVectorStore.IndexSnapshot next = snapshot(projectId, "snap-2", 2L,
                indexed(projectId, "snap-2", "doc-new", 0.0, 1.0, 0.0));

        StaleSemanticSyncException moved = assertThrows(StaleSemanticSyncException.class,
                () -> store.replaceConditionally(next, "snap-2", () -> Optional.of("snap-3")));
        StaleSemanticSyncException absent = assertThrows(StaleSemanticSyncException.class,
                () -> store.replaceConditionally(next, "snap-2", Optional::empty));

        assertTrue(moved.getMessage().contains("built for snapshot snap-2")
                && moved.getMessage().contains("active snapshot is now snap-3"), moved.getMessage());
        assertTrue(absent.getMessage().contains("active snapshot is now absent"), absent.getMessage());
        SemanticVectorStore.IndexSnapshot kept = store.load(projectId).orElseThrow();
        assertEquals("snap-1", kept.snapshotId());
        assertEquals(List.of("doc-old"), kept.documents().stream().map(value -> value.document().stableKey()).toList());
        assertEquals(0, connections.poolStats().leased());
    }

    @Test
    void aFailingActiveSnapshotReaderAbortsTheConditionalReplacement() throws Exception {
        String projectId = UUID.randomUUID().toString();
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);
        store.replace(snapshot(projectId, "snap-1", 1L, indexed(projectId, "snap-1", "doc-old", 1.0, 0.0, 0.0)));
        SemanticVectorStore.IndexSnapshot next = snapshot(projectId, "snap-2", 2L);
        SemanticVectorStore.ActiveSnapshotIdReader failing = () -> {
            throw new IOException("active snapshot unavailable");
        };

        IOException failure = assertThrows(IOException.class, () -> store.replaceConditionally(next, "snap-2", failing));

        assertEquals("active snapshot unavailable", failure.getMessage());
        assertEquals("snap-1", store.load(projectId).orElseThrow().snapshotId());
    }

    @Test
    void aConditionalReplacementWithoutAnExpectedSnapshotOrAReaderIsRefused() throws Exception {
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(closedFactory());
        String projectId = UUID.randomUUID().toString();
        SemanticVectorStore.IndexSnapshot next = snapshot(projectId, "snap-2", 2L);
        SemanticVectorStore.ActiveSnapshotIdReader reader = () -> Optional.of("snap-2");

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> store.replaceConditionally(next, null, reader));
        IllegalArgumentException blank = assertThrows(IllegalArgumentException.class,
                () -> store.replaceConditionally(next, "  ", reader));
        assertThrows(NullPointerException.class, () -> store.replaceConditionally(null, "snap-2", reader));
        assertThrows(NullPointerException.class, () -> store.replaceConditionally(next, "snap-2", null));
        assertThrows(NullPointerException.class, () -> store.replace(null));
        assertThrows(NullPointerException.class, () -> new PostgresSemanticVectorStore(null));

        assertEquals("expectedActiveSnapshotId must not be blank", missing.getMessage());
        assertEquals("expectedActiveSnapshotId must not be blank", blank.getMessage());
    }

    @Test
    void storedVectorsThatDoNotMatchTheDeclaredDimensionsFailTheLoad() throws Exception {
        UUID id = UUID.randomUUID();
        String projectId = id.toString();
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);
        store.replace(snapshot(projectId, "snap-1", 1L, indexed(projectId, "snap-1", "doc-a", 1.0, 0.0, 0.0)));
        connections.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE semantic_index_meta SET dimensions=5 WHERE project_id=?")) {
                statement.setObject(1, id);
                return statement.executeUpdate();
            }
        });

        IOException failure = assertThrows(IOException.class, () -> store.load(projectId));

        assertEquals("pgvector dimensions mismatch", failure.getMessage());
    }

    @Test
    void aStorageFailureIsReportedAsAnIoExceptionNamingTheOperation() throws Exception {
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(closedFactory());
        String projectId = UUID.randomUUID().toString();
        SemanticVectorStore.IndexSnapshot snapshot = snapshot(projectId, "snap-1", 1L);
        SemanticVectorStore.ActiveSnapshotIdReader reader = () -> Optional.of("snap-1");

        IOException metadata = assertThrows(IOException.class, () -> store.metadata(projectId));
        IOException load = assertThrows(IOException.class, () -> store.load(projectId));
        IOException replace = assertThrows(IOException.class, () -> store.replace(snapshot));
        IOException conditional = assertThrows(IOException.class,
                () -> store.replaceConditionally(snapshot, "snap-1", reader));
        IOException delete = assertThrows(IOException.class, () -> store.delete(projectId));
        IOException size = assertThrows(IOException.class, () -> store.sizeBytes(projectId));

        assertEquals("unable to load pgvector semantic metadata", metadata.getMessage());
        assertEquals("unable to load pgvector semantic index", load.getMessage());
        assertEquals("unable to replace pgvector semantic index", replace.getMessage());
        assertEquals("unable to conditionally replace pgvector semantic index", conditional.getMessage());
        assertEquals("unable to delete pgvector semantic index", delete.getMessage());
        assertEquals("unable to measure pgvector semantic index", size.getMessage());
        for (IOException failure : List.of(metadata, load, replace, conditional, delete, size)) {
            assertInstanceOf(SQLException.class, failure.getCause());
        }
        assertEquals(Optional.empty(), new PostgresSemanticVectorStore(connections).metadata(projectId),
                "nothing may be written through a closed pool");
    }

    private PostgresConnectionFactory closedFactory() throws IOException {
        PostgresConnectionFactory factory = createFactory("minos");
        factory.close();
        return factory;
    }

    private void activateKnowledgeSnapshot(UUID projectId, String snapshotId) throws Exception {
        connections.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO knowledge_active(project_id,snapshot_id) VALUES (?,?)")) {
                statement.setObject(1, projectId);
                statement.setString(2, snapshotId);
                return statement.executeUpdate();
            }
        });
    }

    private static List<String> keys(List<SemanticVectorStore.VectorHit> hits) {
        return hits.stream().map(hit -> hit.document().stableKey()).toList();
    }

    private static SemanticVectorStore.IndexSnapshot snapshot(
            String projectId, String snapshotId, long builtAt, SemanticVectorStore.IndexedDocument... documents) {
        return new SemanticVectorStore.IndexSnapshot(
                projectId, snapshotId, "test-provider", "test-model", DIMENSIONS, builtAt, List.of(documents));
    }

    private static SemanticVectorStore.IndexedDocument indexed(
            String projectId, String snapshotId, String stableKey, double first, double second, double third) {
        SemanticDocument document = new SemanticDocument(
                stableKey + "-id", stableKey, projectId, snapshotId,
                SemanticDocumentKind.SYMBOL, "Service.java", "Service.java",
                1, 10, "public void " + stableKey + "()", "chk-" + stableKey);
        return new SemanticVectorStore.IndexedDocument(
                document, SemanticVector.fromArray(stableKey, new double[]{first, second, third}));
    }
}
