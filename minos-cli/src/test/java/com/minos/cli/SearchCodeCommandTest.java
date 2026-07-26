package com.minos.cli;

import com.minos.context.CodeSearchCriteria;
import com.minos.context.CodeSearchResponse;
import com.minos.domain.SymbolSearchCriteria;
import com.minos.query.SymbolResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchCodeCommandTest {

    @Test
    void parsesStructuredLimitsAndRendersJson() throws IOException {
        AtomicReference<CodeSearchCriteria> captured = new AtomicReference<>();
        SearchCodeCommand command = new SearchCodeCommand(new EmptyProjectQuery() {
            @Override
            public CodeSearchResponse searchCode(String projectId, CodeSearchCriteria criteria) {
                assertEquals("project-1", projectId);
                captured.set(criteria);
                return new CodeSearchResponse(
                        projectId, criteria.symbols().text(), criteria.maxDepth(),
                        criteria.maxTokens(), 24, 0, false, List.of());
            }
        });
        StringBuilder output = new StringBuilder();

        int code = command.run(new String[]{
                "project-1", "Greeting", "--qualified-name", "com.minos.Greeting",
                "--kind", "class", "--module", "main", "--limit", "4",
                "--depth", "2", "--usages", "7", "--relationships", "8",
                "--context-lines", "5", "--max-tokens", "512", "--no-source",
                "--format", "json"
        }, output, new StringBuilder());

        assertEquals(0, code);
        assertEquals(2, captured.get().maxDepth());
        assertEquals(7, captured.get().usagesPerSymbol());
        assertEquals(8, captured.get().relationshipsPerNode());
        assertEquals(5, captured.get().contextLines());
        assertEquals(512, captured.get().maxTokens());
        assertEquals(4, captured.get().symbols().limit());
        assertFalse(captured.get().includeSource());
        assertEquals("{\"projectId\":\"project-1\",\"query\":\"Greeting\","
                + "\"count\":0,\"maxDepth\":2,\"tokenBudget\":512,"
                + "\"estimatedTokens\":24,\"estimatedTokensAvoided\":0,"
                + "\"truncated\":false,\"contexts\":[]}\n", output.toString());
    }

    @Test
    void rejectsOutOfBoundsAndSanitizesExecutionFailures() throws IOException {
        SearchCodeCommand command = new SearchCodeCommand(new EmptyProjectQuery() {
            @Override
            public CodeSearchResponse searchCode(String projectId, CodeSearchCriteria criteria) {
                throw new IllegalStateException("broken\nsnapshot");
            }
        });
        StringBuilder invalid = new StringBuilder();

        assertEquals(2, command.run(
                new String[]{"project", "query", "--depth", "4"},
                new StringBuilder(), invalid));
        assertTrue(invalid.toString().startsWith("error: --depth must be between 0 and 3"));

        StringBuilder failure = new StringBuilder();
        assertEquals(1, command.run(
                new String[]{"project", "query"}, new StringBuilder(), failure));
        assertEquals("error: search failed: broken snapshot\n", failure.toString());
    }

    private abstract static class EmptyProjectQuery implements ProjectSymbolQuery {
        @Override
        public List<SymbolResult> findSymbols(String projectId, SymbolSearchCriteria criteria) {
            return List.of();
        }
    }
}
