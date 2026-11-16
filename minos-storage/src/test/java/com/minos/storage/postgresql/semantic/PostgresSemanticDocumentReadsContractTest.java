package com.minos.storage.postgresql.semantic;

import com.minos.semantic.SemanticVectorStore;
import com.minos.testsupport.SemanticDocumentReadsContract;
import com.minos.storage.postgresql.PostgresTestSupport;
import com.minos.storage.postgresql.support.PostgresConnectionFactory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The paged document reads and the stored vector read, on the pgvector store. */
class PostgresSemanticDocumentReadsContractTest extends PostgresTestSupport implements SemanticDocumentReadsContract {

    @Override
    public SemanticVectorStore store() {
        return new PostgresSemanticVectorStore(connections);
    }

    @Override
    public String newProjectId() {
        return UUID.randomUUID().toString();
    }

    /**
     * The page is read without the embedding column: an index whose declared dimensions no longer match its
     * stored vectors cannot be loaded, and its page is still read.
     */
    @Test
    void aPageIsReadWithoutTheEmbeddingColumn() throws Exception {
        UUID id = UUID.randomUUID();
        String projectId = id.toString();
        SemanticVectorStore store = store();
        SemanticVectorStore.IndexedDocument stored =
                SemanticDocumentReadsContract.indexed(projectId, "snap-1", "doc-a", 1.0, 0.0, 0.0);
        store.replace(SemanticDocumentReadsContract.snapshot(projectId, "snap-1", 1L, List.of(stored)));
        connections.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE semantic_index_meta SET dimensions=5 WHERE project_id=?")) {
                statement.setObject(1, id);
                return statement.executeUpdate();
            }
        });

        SemanticVectorStore.DocumentPage page = store.documentPage(projectId, null, 10).orElseThrow();
        IOException fullLoad = assertThrows(IOException.class, () -> store.load(projectId));
        IOException vector = assertThrows(IOException.class, () -> store.storedDocument(projectId, "doc-a"));

        assertEquals(List.of(stored.document()), page.documents());
        assertEquals(5, page.metadata().dimensions());
        assertEquals("pgvector dimensions mismatch", fullLoad.getMessage());
        assertEquals("pgvector dimensions mismatch", vector.getMessage());
    }

    /** The refusal comes before any connection: a factory that is closed would fail with an I/O error instead. */
    @Test
    void aBlankResumeKeyIsRefusedBeforeAnyStatementIsSent() throws Exception {
        PostgresConnectionFactory factory = createFactory("minos");
        factory.close();
        PostgresSemanticVectorStore closed = new PostgresSemanticVectorStore(factory);
        String projectId = UUID.randomUUID().toString();

        assertThrows(IllegalArgumentException.class, () -> closed.documentPage(projectId, " ", 10));
        assertThrows(IllegalArgumentException.class, () -> closed.vectorPage(projectId, "", 10));
    }

    /** Two pages of one index, one on each side of a replacement, do not carry the same metadata. */
    @Test
    void twoPagesReadOnEachSideOfAReplacementCarryDifferentMetadata() throws Exception {
        String projectId = UUID.randomUUID().toString();
        SemanticVectorStore store = store();
        List<SemanticVectorStore.IndexedDocument> before = new ArrayList<>();
        for (int index = 0; index < 120; index++) {
            before.add(SemanticDocumentReadsContract.indexed(projectId, "snap-1",
                    String.format(java.util.Locale.ROOT, "doc-%03d", index), index, 0.0, 1.0));
        }
        store.replace(SemanticDocumentReadsContract.snapshot(projectId, "snap-1", 5L, before));
        SemanticVectorStore.VectorPage first = store.vectorPage(projectId, null, 20).orElseThrow();

        List<SemanticVectorStore.IndexedDocument> after = new ArrayList<>();
        for (int index = 0; index < 80; index++) {
            after.add(SemanticDocumentReadsContract.indexed(projectId, "snap-2",
                    String.format(java.util.Locale.ROOT, "doc-%03d", index), index, 0.0, 1.0));
        }
        store.replace(SemanticDocumentReadsContract.snapshot(projectId, "snap-2", 6L, after));
        SemanticVectorStore.VectorPage second = store.vectorPage(projectId, first.vectors().getLast().stableKey(), 20)
                .orElseThrow();

        assertEquals(120, first.metadata().documentCount());
        assertEquals(80, second.metadata().documentCount());
        assertEquals("snap-1", first.metadata().snapshotId());
        assertEquals("snap-2", second.metadata().snapshotId());
        assertNotEquals(first.metadata(), second.metadata());
    }

    @Test
    void aFailedPagedReadIsReportedAsAnIoExceptionNamingTheOperation() throws Exception {
        PostgresConnectionFactory factory = createFactory("minos");
        factory.close();
        PostgresSemanticVectorStore closed = new PostgresSemanticVectorStore(factory);
        String projectId = UUID.randomUUID().toString();

        IOException page = assertThrows(IOException.class, () -> closed.documentPage(projectId, null, 10));
        IOException vector = assertThrows(IOException.class, () -> closed.storedDocument(projectId, "doc-a"));
        IOException invalid = assertThrows(IOException.class, () -> closed.documentPage("not-a-uuid", null, 10));

        assertEquals("unable to load pgvector semantic document page", page.getMessage());
        assertInstanceOf(SQLException.class, page.getCause());
        assertEquals("unable to load pgvector semantic document", vector.getMessage());
        assertEquals("semantic project id must be a UUID", invalid.getMessage());
    }
}
