package com.minos.storage.postgresql.semantic;

import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore;
import com.minos.storage.postgresql.PostgresTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A synchronization written as a differential leaves exactly the rows a full replacement of the same target
 * would leave, sends a vector only for a new or changed document, and leaves the previous index intact when
 * it fails part-way or when it asks for a reuse the store cannot honour.
 */
class PostgresSemanticDifferentialSyncTest extends PostgresTestSupport {

    private static final int DIMS = 3;

    @AfterEach
    void dropProbes() throws Exception {
        if (connections == null) return;
        execute("DROP TRIGGER IF EXISTS test_vector_insert ON semantic_documents",
                "DROP TRIGGER IF EXISTS test_vector_update ON semantic_documents",
                "DROP TRIGGER IF EXISTS test_failing_insert ON semantic_documents",
                "DROP FUNCTION IF EXISTS test_log_vector_write()",
                "DROP FUNCTION IF EXISTS test_fail_insert()",
                "DROP TABLE IF EXISTS test_vector_writes");
    }

    @Test
    void anAddedDocumentLeavesTheRowsOfAFullReplacement() throws Exception {
        assertEquivalent(target("snap-1", "m1", doc("a", "alpha", 1), doc("b", "beta", 3)),
                target("snap-2", "m1", doc("a", "alpha", 1), doc("b", "beta", 3), doc("c", "gamma", 5)), 2);
    }

    @Test
    void aChangedDocumentLeavesTheRowsOfAFullReplacement() throws Exception {
        assertEquivalent(target("snap-1", "m1", doc("a", "alpha", 1), doc("b", "beta", 3)),
                target("snap-2", "m1", doc("a", "alpha", 1), doc("b", "beta changed", 3)), 1);
    }

    @Test
    void aRemovedDocumentLeavesTheRowsOfAFullReplacement() throws Exception {
        assertEquivalent(target("snap-1", "m1", doc("a", "alpha", 1), doc("b", "beta", 3), doc("c", "gamma", 5)),
                target("snap-2", "m1", doc("a", "alpha", 1)), 1);
    }

    @Test
    void aMovedDocumentLeavesTheRowsOfAFullReplacement() throws Exception {
        assertEquivalent(target("snap-1", "m1", doc("a", "alpha", 1), doc("b", "beta", 3)),
                target("snap-2", "m1", doc("a", "alpha", 10), doc("b", "beta", 30)), 2);
    }

    @Test
    void anotherModelLeavesTheRowsOfAFullReplacement() throws Exception {
        assertEquivalent(target("snap-1", "m1", doc("a", "alpha", 1), doc("b", "beta", 3)),
                target("snap-1", "m2", doc("a", "alpha", 1), doc("b", "beta", 3)), 0);
    }

    @Test
    void anEmptyTargetLeavesTheRowsOfAFullReplacement() throws Exception {
        assertEquivalent(target("snap-1", "m1", doc("a", "alpha", 1), doc("b", "beta", 3)),
                target("snap-2", "m1"), 0);
    }

    @Test
    void anEntirelyReusedTargetLeavesTheRowsOfAFullReplacement() throws Exception {
        assertEquivalent(target("snap-1", "m1", doc("a", "alpha", 1), doc("b", "beta", 3), doc("c", "gamma", 5)),
                target("snap-2", "m1", doc("a", "alpha", 1), doc("b", "beta", 3), doc("c", "gamma", 5)), 3);
    }

    @Test
    void oneChangedDocumentOutOfNIsTheOnlyVectorSent() throws Exception {
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);
        String project = UUID.randomUUID().toString();
        Target before = target("snap-1", "m1", doc("a", "alpha", 1), doc("b", "beta", 2), doc("c", "gamma", 3),
                doc("d", "delta", 4), doc("e", "epsilon", 5));
        store.replace(before.full(project));
        installVectorWriteProbe();

        Target after = target("snap-2", "m1", doc("a", "alpha", 1), doc("b", "beta", 2), doc("c", "gamma changed", 3),
                doc("d", "delta", 4), doc("e", "epsilon", 5));
        store.replaceConditionally(after.differential(project, store), "snap-2", () -> Optional.of("snap-2"));

        assertEquals(List.of("c"), vectorWrites());
        assertEquals(rows(project), expectedRowsOfAFullReplacement(after));
    }

    @Test
    void aFailureAfterTheRemovalOfDocumentsLeavesThePreviousIndex() throws Exception {
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);
        String project = UUID.randomUUID().toString();
        Target before = target("snap-1", "m1", doc("a", "alpha", 1), doc("b", "beta", 2), doc("c", "gamma", 3));
        store.replace(before.full(project));
        List<List<String>> rowsBefore = rows(project);
        List<String> metadataBefore = metadataRow(project);
        execute("CREATE FUNCTION test_fail_insert() RETURNS trigger AS $$ BEGIN "
                        + "IF NEW.stable_key = 'boom' THEN RAISE EXCEPTION 'injected failure'; END IF; "
                        + "RETURN NEW; END $$ LANGUAGE plpgsql",
                "CREATE TRIGGER test_failing_insert BEFORE INSERT ON semantic_documents "
                        + "FOR EACH ROW EXECUTE FUNCTION test_fail_insert()");

        // b and c are removed before boom is inserted.
        Target failing = target("snap-2", "m1", doc("a", "alpha", 1), doc("boom", "boom", 9));
        SemanticVectorStore.IndexSnapshot next = failing.differential(project, store);

        assertThrows(IOException.class,
                () -> store.replaceConditionally(next, "snap-2", () -> Optional.of("snap-2")));
        assertEquals(rowsBefore, rows(project));
        assertEquals(metadataBefore, metadataRow(project));
    }

    @Test
    void aReuseTheStoreCannotHonourLeavesThePreviousIndex() throws Exception {
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);
        String project = UUID.randomUUID().toString();
        store.replace(target("snap-1", "m1", doc("a", "alpha", 1)).full(project));
        List<List<String>> rowsBefore = rows(project);
        SemanticVectorStore.IndexSnapshot changedContent = new SemanticVectorStore.IndexSnapshot(project, "snap-2",
                "provider", "m1", DIMS, 2_000L, List.of(SemanticVectorStore.IndexedDocument.reusingStoredVector(
                        doc("a", "alpha changed", 1).document(project, "snap-2"))));
        SemanticVectorStore.IndexSnapshot otherModel = new SemanticVectorStore.IndexSnapshot(project, "snap-2",
                "provider", "m2", DIMS, 2_000L, List.of(SemanticVectorStore.IndexedDocument.reusingStoredVector(
                        doc("a", "alpha", 1).document(project, "snap-2"))));

        assertThrows(IOException.class,
                () -> store.replaceConditionally(changedContent, "snap-2", () -> Optional.of("snap-2")));
        assertThrows(IOException.class,
                () -> store.replaceConditionally(otherModel, "snap-2", () -> Optional.of("snap-2")));
        assertEquals(rowsBefore, rows(project));
        assertThrows(NullPointerException.class, () -> new SemanticVectorStore.IndexedDocument(
                doc("a", "alpha", 1).document(project, "snap-2"), null));
    }

    private void assertEquivalent(Target before, Target after, int expectedReuses) throws Exception {
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);
        String differential = UUID.randomUUID().toString();
        String witness = UUID.randomUUID().toString();
        store.replace(before.full(differential));
        store.replace(before.full(witness));

        SemanticVectorStore.IndexSnapshot next = after.differential(differential, store);
        store.replaceConditionally(next, after.snapshot, () -> Optional.of(after.snapshot));
        store.delete(witness);
        store.replace(after.full(witness));

        assertEquals(expectedReuses, next.documents().stream()
                .filter(SemanticVectorStore.IndexedDocument::reusesStoredVector).count(), "documents reused");
        assertEquals(rows(witness), rows(differential));
        assertEquals(metadataRow(witness), metadataRow(differential));
        assertEquals(expectedRowsOfAFullReplacement(after), rows(differential));
        SemanticVectorStore.IndexSnapshot loaded = store.load(differential).orElseThrow();
        assertEquals(after.snapshot, loaded.snapshotId());
        assertEquals(after.documents.size(), loaded.documents().size());
    }

    private static List<List<String>> expectedRowsOfAFullReplacement(Target target) {
        List<List<String>> rows = new ArrayList<>();
        for (Doc doc : target.documents) {
            SemanticDocument document = doc.document("ignored", target.snapshot);
            rows.add(List.of(document.stableKey(), document.id(), document.snapshotId(), document.kind().name(),
                    document.sourceId(), document.fileId(), Integer.toString(document.startLine()),
                    Integer.toString(document.endLine()), document.content(), document.checksum(),
                    PostgresSemanticWriteQueries.vectorLiteral(doc.vector(target.model))));
        }
        return rows;
    }

    private List<List<String>> rows(String project) throws Exception {
        return connections.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT stable_key,document_id,snapshot_id,kind,source_id,file_id,start_line,end_line,content,"
                            + "checksum,embedding::text FROM semantic_documents WHERE project_id=? ORDER BY stable_key")) {
                statement.setObject(1, UUID.fromString(project));
                List<List<String>> rows = new ArrayList<>();
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        List<String> row = new ArrayList<>();
                        for (int column = 1; column <= 11; column++) row.add(result.getString(column));
                        rows.add(row);
                    }
                }
                return rows;
            }
        });
    }

    private List<String> metadataRow(String project) throws Exception {
        return connections.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT snapshot_id,provider_id,model_id,dimensions,built_at FROM semantic_index_meta WHERE project_id=?")) {
                statement.setObject(1, UUID.fromString(project));
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) return List.of();
                    List<String> row = new ArrayList<>();
                    for (int column = 1; column <= 5; column++) row.add(result.getString(column));
                    return row;
                }
            }
        });
    }

    /** Logs the key of every row inserted or updated with its embedding column in the SET list. */
    private void installVectorWriteProbe() throws Exception {
        execute("CREATE TABLE test_vector_writes(stable_key text NOT NULL)",
                "CREATE FUNCTION test_log_vector_write() RETURNS trigger AS $$ BEGIN "
                        + "INSERT INTO test_vector_writes VALUES (NEW.stable_key); RETURN NEW; END $$ LANGUAGE plpgsql",
                "CREATE TRIGGER test_vector_insert BEFORE INSERT ON semantic_documents "
                        + "FOR EACH ROW EXECUTE FUNCTION test_log_vector_write()",
                "CREATE TRIGGER test_vector_update BEFORE UPDATE OF embedding ON semantic_documents "
                        + "FOR EACH ROW EXECUTE FUNCTION test_log_vector_write()");
    }

    private List<String> vectorWrites() throws Exception {
        return connections.withConnection(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT DISTINCT stable_key FROM test_vector_writes ORDER BY stable_key")) {
                List<String> keys = new ArrayList<>();
                while (result.next()) keys.add(result.getString(1));
                return keys;
            }
        });
    }

    private void execute(String... statements) throws Exception {
        connections.withConnection(connection -> {
            try (Statement statement = connection.createStatement()) {
                for (String sql : statements) statement.execute(sql);
            }
            return null;
        });
    }

    private static Doc doc(String key, String content, int line) {
        return new Doc(key, content, line);
    }

    private static Target target(String snapshot, String model, Doc... documents) {
        return new Target(snapshot, model, List.of(documents));
    }

    private record Doc(String key, String content, int line) {
        String checksum() {
            return "sum-" + content;
        }

        SemanticDocument document(String project, String snapshot) {
            return new SemanticDocument("doc-" + snapshot + "-" + key, key, project, snapshot,
                    SemanticDocumentKind.SYMBOL, "source-" + key + "-" + line, "file-" + key, line, line + 2,
                    content, checksum());
        }

        /** The embedding of a content by a model: the same inputs always give the same vector. */
        SemanticVector vector(String model) {
            int seed = (model + "/" + content).hashCode();
            return SemanticVector.fromArray(key, new double[] {
                    (seed & 0xFF) / 255.0, ((seed >> 8) & 0xFF) / 255.0, ((seed >> 16) & 0xFF) / 255.0 + 0.5});
        }
    }

    private record Target(String snapshot, String model, List<Doc> documents) {
        SemanticVectorStore.IndexSnapshot full(String project) {
            List<SemanticVectorStore.IndexedDocument> indexed = new ArrayList<>();
            for (Doc doc : documents) {
                indexed.add(new SemanticVectorStore.IndexedDocument(doc.document(project, snapshot), doc.vector(model)));
            }
            return snapshot(project, indexed);
        }

        /** What the synchronization sends: a document whose checksum the index holds for this model is reused. */
        SemanticVectorStore.IndexSnapshot differential(String project, SemanticVectorStore store) throws IOException {
            Map<String, String> stored = store.checksums(project)
                    .filter(index -> index.metadata().modelId().equals(model))
                    .map(SemanticVectorStore.IndexChecksums::checksumsByStableKey)
                    .orElse(Map.of());
            List<SemanticVectorStore.IndexedDocument> indexed = new ArrayList<>();
            for (Doc doc : documents) {
                SemanticDocument document = doc.document(project, snapshot);
                indexed.add(doc.checksum().equals(stored.get(doc.key()))
                        ? SemanticVectorStore.IndexedDocument.reusingStoredVector(document)
                        : new SemanticVectorStore.IndexedDocument(document, doc.vector(model)));
            }
            return snapshot(project, indexed);
        }

        private SemanticVectorStore.IndexSnapshot snapshot(String project,
                                                           List<SemanticVectorStore.IndexedDocument> indexed) {
            Map<String, SemanticVectorStore.IndexedDocument> ordered = new LinkedHashMap<>();
            indexed.stream().sorted((left, right) -> left.document().stableKey().compareTo(right.document().stableKey()))
                    .forEach(value -> ordered.put(value.document().stableKey(), value));
            return new SemanticVectorStore.IndexSnapshot(project, snapshot, "provider", model, DIMS, 2_000L,
                    List.copyOf(ordered.values()));
        }
    }

    @Test
    void theChecksumsAreReadWithoutTheVectorsAndWithTheMetadataOfTheIndex() throws Exception {
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);
        String project = UUID.randomUUID().toString();
        assertTrue(store.checksums(project).isEmpty());
        store.replace(target("snap-1", "m1", doc("b", "beta", 3), doc("a", "alpha", 1)).full(project));

        SemanticVectorStore.IndexChecksums checksums = store.checksums(project).orElseThrow();

        assertEquals(Map.of("a", "sum-alpha", "b", "sum-beta"), checksums.checksumsByStableKey());
        assertEquals(store.metadata(project).orElseThrow(), checksums.metadata());
    }
}
