package com.minos.storage.postgresql.semantic;

import com.minos.semantic.SemanticVectorStore;
import com.minos.storage.postgresql.PostgresTestSupport;
import com.minos.testsupport.SemanticDocumentReadsContract;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plan the server chooses for the read statements of the semantic index: a page, a vector page and a document
 * are read without counting the rows of the index, and a page that follows a key is bounded by that key in the
 * index, also when the server runs the statement in generic-plan mode. Each statement is prepared on the server
 * ({@code PREPARE}) and its plan read by {@code EXPLAIN (ANALYZE) EXECUTE}: a plan read for literal values
 * would say nothing about the plan a prepared statement keeps.
 */
class PostgresSemanticPagePlanTest extends PostgresTestSupport {

    private static final int DOCUMENTS = 3_000;
    private static final int PAGE = 200;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\?");

    @Test
    void noPlanOfAReadStatementCountsTheRowsOfTheIndex() throws Exception {
        UUID id = indexOf(DOCUMENTS);
        String after = key(1_999);

        List<String> plans = List.of(
                plan(PostgresSemanticReadQueries.DOCUMENT_FIRST_PAGE_SQL, PAGE + 1, id),
                plan(PostgresSemanticReadQueries.DOCUMENT_NEXT_PAGE_SQL, after, PAGE + 1, id),
                plan(PostgresSemanticReadQueries.VECTOR_FIRST_PAGE_SQL, PAGE + 1, id),
                plan(PostgresSemanticReadQueries.VECTOR_NEXT_PAGE_SQL, after, PAGE + 1, id),
                plan(PostgresSemanticReadQueries.STORED_DOCUMENT_SQL, key(1_500), id),
                plan(PostgresSemanticReadQueries.METADATA_SQL, id));

        for (String plan : plans) {
            assertFalse(plan.toLowerCase(Locale.ROOT).contains("aggregate"), "an aggregate counts the index:\n" + plan);
        }
    }

    @Test
    void aPageThatFollowsAKeyIsBoundedByTheKeyInTheIndexEvenInAGenericPlan() throws Exception {
        UUID id = indexOf(DOCUMENTS);
        String after = key(1_999);

        for (String sql : List.of(PostgresSemanticReadQueries.DOCUMENT_NEXT_PAGE_SQL,
                PostgresSemanticReadQueries.VECTOR_NEXT_PAGE_SQL)) {
            String plan = plan(sql, after, PAGE + 1, id);
            assertTrue(Pattern.compile("Index Cond:[^\\n]*stable_key > ").matcher(plan).find(),
                    "the plan must bound the page by an index condition on stable_key:\n" + plan);
            assertFalse(Pattern.compile("Filter:[^\\n]*stable_key").matcher(plan).find(),
                    "the plan must not discard the earlier rows by a filter on stable_key:\n" + plan);
            assertFalse(Pattern.compile("Rows Removed by Filter: [1-9]").matcher(plan).find(),
                    "the plan must not read rows only to discard them:\n" + plan);
        }
    }

    @Test
    void everyPageAnnouncesTheCountOfTheIndex() throws Exception {
        UUID id = indexOf(DOCUMENTS);
        PostgresSemanticVectorStore store = new PostgresSemanticVectorStore(connections);

        SemanticVectorStore.DocumentPage documents = store.documentPage(id.toString(), key(1_999), PAGE).orElseThrow();
        SemanticVectorStore.VectorPage vectors = store.vectorPage(id.toString(), null, PAGE).orElseThrow();
        SemanticVectorStore.StoredDocument stored = store.storedDocument(id.toString(), key(10)).orElseThrow();

        assertEquals(DOCUMENTS, documents.metadata().documentCount());
        assertEquals(DOCUMENTS, vectors.metadata().documentCount());
        assertEquals(DOCUMENTS, stored.metadata().documentCount());
        assertEquals(DOCUMENTS, store.metadata(id.toString()).orElseThrow().documentCount());
    }

    /** The plan text of {@code sql} prepared on the server and executed with {@code values}, generic plan forced. */
    private String plan(String sql, Object... values) throws Exception {
        return connections.withConnection(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET plan_cache_mode = force_generic_plan");
                try {
                    statement.execute("PREPARE page_plan AS " + numbered(sql));
                    List<String> lines = new ArrayList<>();
                    try (ResultSet result = statement.executeQuery(
                            "EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF) EXECUTE page_plan(" + literals(values) + ")")) {
                        while (result.next()) lines.add(result.getString(1));
                    }
                    return String.join("\n", lines);
                } finally {
                    statement.execute("DEALLOCATE ALL");
                    statement.execute("RESET plan_cache_mode");
                }
            }
        });
    }

    /** {@code ?} placeholders of a JDBC statement as the numbered parameters of a server-side {@code PREPARE}. */
    private static String numbered(String sql) {
        Matcher matcher = PLACEHOLDER.matcher(sql);
        StringBuilder numbered = new StringBuilder();
        int index = 0;
        while (matcher.find()) matcher.appendReplacement(numbered, "\\$" + (++index));
        matcher.appendTail(numbered);
        return numbered.toString();
    }

    /** SQL literals of values the test itself generated: keys, numbers and the project identifier. */
    private static String literals(Object[] values) {
        List<String> literals = new ArrayList<>();
        for (Object value : values) {
            if (value == null) literals.add("NULL");
            else if (value instanceof Number) literals.add(value.toString());
            else literals.add("'" + value + "'");
        }
        return String.join(",", literals);
    }

    private UUID indexOf(int count) throws Exception {
        UUID id = UUID.randomUUID();
        String projectId = id.toString();
        List<SemanticVectorStore.IndexedDocument> documents = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            documents.add(SemanticDocumentReadsContract.indexed(projectId, "snap-1", key(index), index, 1.0, -1.0));
        }
        new PostgresSemanticVectorStore(connections)
                .replace(SemanticDocumentReadsContract.snapshot(projectId, "snap-1", 5L, documents));
        return id;
    }

    private static String key(int index) {
        return String.format(Locale.ROOT, "doc-%05d", index);
    }
}
