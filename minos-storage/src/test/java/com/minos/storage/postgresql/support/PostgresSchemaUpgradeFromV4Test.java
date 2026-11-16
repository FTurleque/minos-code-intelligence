package com.minos.storage.postgresql.support;

import com.minos.orchestration.ProjectIndexState;
import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticVectorStore;
import com.minos.storage.postgresql.PostgresTestSupport;
import com.minos.storage.postgresql.project.PostgresIndexStateStore;
import com.minos.storage.postgresql.semantic.PostgresSemanticVectorStore;
import com.minos.testsupport.SemanticDocumentReadsContract;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ADR 0039 §2: a database at schema v4, whose project states have no resume column, migrates to v5; the existing
 * state is read back without a resume offer, and a new offer is then kept. The same migration reaches v6, which
 * records the document count of each semantic index.
 */
class PostgresSchemaUpgradeFromV4Test extends PostgresTestSupport {

    @Test
    void aPopulatedV4DatabaseGainsTheResumeColumnAndKeepsItsStates() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID latestRunId = UUID.randomUUID();
        PostgresIndexStateStore store = new PostgresIndexStateStore(connections, new PostgresJsonCodec());
        store.saveProjectState(new ProjectIndexState(projectId, ProjectIndexState.Availability.FAILED,
                Optional.empty(), Optional.of(latestRunId), Instant.parse("2026-10-01T00:00:00Z"),
                Optional.of("failed")));
        downgradeToV4();

        new PostgresSchemaMigrator(connections).migrate();

        assertEquals(List.of(1, 2, 3, 4, 5, 6), versions());
        ProjectIndexState migrated = store.findProjectState(projectId).orElseThrow();
        assertEquals(Optional.of(latestRunId), migrated.latestRunId());
        assertEquals(Optional.empty(), migrated.resumableRunId());

        UUID interrupted = UUID.randomUUID();
        store.saveProjectState(new ProjectIndexState(projectId, ProjectIndexState.Availability.FAILED,
                Optional.empty(), Optional.of(interrupted), Instant.parse("2026-10-02T00:00:00Z"),
                Optional.of("interrupted"), Optional.of(interrupted)));
        assertEquals(Optional.of(interrupted),
                store.findProjectState(projectId).orElseThrow().resumableRunId());
    }

    @Test
    void aV4DatabaseRecordsTheDocumentCountOfItsSemanticIndexWithoutResynchronizing() throws Exception {
        UUID id = UUID.randomUUID();
        String projectId = id.toString();
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);
        List<SemanticVectorStore.IndexedDocument> documents = new ArrayList<>();
        for (int index = 0; index < 250; index++) {
            documents.add(SemanticDocumentReadsContract.indexed(projectId, "snap-1",
                    String.format(Locale.ROOT, "doc-%03d", index), index, 1.0, -1.0));
        }
        store.replace(SemanticDocumentReadsContract.snapshot(projectId, "snap-1", 5L, documents));
        List<String> keysBefore = firstPageKeys(store, projectId);
        downgradeToV4();

        new PostgresSchemaMigrator(connections).migrate();

        assertEquals(250, documentCountColumn(id));
        assertEquals(250, store.documentPage(projectId, null, 10).orElseThrow().metadata().documentCount());
        assertEquals(keysBefore, firstPageKeys(store, projectId));
        assertEquals(List.of(1, 2, 3, 4, 5, 6), versions());
    }

    @Test
    void theMigrationReplayedKeepsTheDocumentCountAndTheVersions() throws Exception {
        UUID id = UUID.randomUUID();
        String projectId = id.toString();
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);
        List<SemanticVectorStore.IndexedDocument> documents = new ArrayList<>();
        for (int index = 0; index < 250; index++) {
            documents.add(SemanticDocumentReadsContract.indexed(projectId, "snap-1",
                    String.format(Locale.ROOT, "doc-%03d", index), index, 1.0, -1.0));
        }
        store.replace(SemanticDocumentReadsContract.snapshot(projectId, "snap-1", 5L, documents));
        assertEquals(250, documentCountColumn(id));

        new PostgresSchemaMigrator(connections).migrate();
        new PostgresSchemaMigrator(connections).migrate();
        assertEquals(250, documentCountColumn(id));
        assertEquals(List.of(1, 2, 3, 4, 5, 6), versions());

        // The step itself replayed on a column already in place: only the version row is missing.
        connections.inTransaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("DELETE FROM schema_version WHERE version = 6");
            }
            return null;
        });
        new PostgresSchemaMigrator(connections).migrate();

        assertEquals(250, documentCountColumn(id));
        assertEquals(List.of(1, 2, 3, 4, 5, 6), versions());
    }

    private static List<String> firstPageKeys(PostgresSemanticVectorStore store, String projectId) throws Exception {
        return store.documentPage(projectId, null, 10).orElseThrow().documents().stream()
                .map(SemanticDocument::stableKey).toList();
    }

    private int documentCountColumn(UUID projectId) throws Exception {
        return connections.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT document_count FROM semantic_index_meta WHERE project_id=?")) {
                statement.setObject(1, projectId);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    return result.getInt(1);
                }
            }
        });
    }

    private void downgradeToV4() throws Exception {
        connections.inTransaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE project_index_state DROP COLUMN resumable_run_id");
                statement.execute("ALTER TABLE semantic_index_meta DROP COLUMN document_count");
                statement.execute("DELETE FROM schema_version WHERE version IN (5, 6)");
            }
            return null;
        });
    }

    private List<Integer> versions() throws Exception {
        return connections.withConnection(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT version FROM schema_version ORDER BY version")) {
                List<Integer> versions = new ArrayList<>();
                while (result.next()) versions.add(result.getInt(1));
                return versions;
            }
        });
    }
}
