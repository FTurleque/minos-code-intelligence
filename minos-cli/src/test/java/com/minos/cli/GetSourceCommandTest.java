package com.minos.cli;

import com.minos.context.SourceExcerpt;
import com.minos.domain.SymbolSearchCriteria;
import com.minos.query.SymbolResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GetSourceCommandTest {

    @Test
    void retrievesCompleteSourceOnlyThroughExplicitCommand() throws IOException {
        GetSourceCommand command = new GetSourceCommand(new EmptyProjectQuery() {
            @Override
            public SourceExcerpt getSource(String projectId, String fileId) {
                assertEquals("project-1", projectId);
                assertEquals("src/Test.java", fileId);
                return new SourceExcerpt(
                        fileId, 1, 2, "class Test {}\n", true, false, 4, 2, 4);
            }
        });
        StringBuilder output = new StringBuilder();

        int code = command.run(new String[]{
                "project-1", "src/Test.java", "--format", "json"
        }, output, new StringBuilder());

        assertEquals(0, code);
        assertTrue(output.toString().contains("\"fullFile\":true"));
        assertTrue(output.toString().contains("class Test {}\\n"));
    }

    @Test
    void rejectsUnknownOptionsAndReportsSafeFailure() throws IOException {
        GetSourceCommand command = new GetSourceCommand(new EmptyProjectQuery() {
            @Override
            public SourceExcerpt getSource(String projectId, String fileId) {
                throw new IllegalArgumentException("outside\nproject");
            }
        });
        StringBuilder syntax = new StringBuilder();
        assertEquals(2, command.run(
                new String[]{"project", "src/Test.java", "--bad"},
                new StringBuilder(), syntax));
        assertTrue(syntax.toString().startsWith("error: unknown option: --bad"));

        StringBuilder failure = new StringBuilder();
        assertEquals(1, command.run(
                new String[]{"project", "src/Test.java"},
                new StringBuilder(), failure));
        assertEquals("error: get-source failed: outside project\n", failure.toString());
    }

    private abstract static class EmptyProjectQuery implements ProjectSymbolQuery {
        @Override
        public List<SymbolResult> findSymbols(String projectId, SymbolSearchCriteria criteria) {
            return List.of();
        }
    }
}
