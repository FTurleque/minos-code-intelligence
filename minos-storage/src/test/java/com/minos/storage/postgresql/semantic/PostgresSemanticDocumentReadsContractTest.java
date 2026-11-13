package com.minos.storage.postgresql.semantic;

import com.minos.semantic.SemanticVectorStore;
import com.minos.testsupport.SemanticDocumentReadsContract;
import com.minos.storage.postgresql.PostgresTestSupport;
import com.minos.storage.postgresql.support.PostgresConnectionFactory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
