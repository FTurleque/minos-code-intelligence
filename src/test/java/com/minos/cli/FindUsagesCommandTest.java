package com.minos.cli;

import com.minos.domain.OccurrenceRole;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.SymbolLocation;
import com.minos.query.UsageResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FindUsagesCommandTest {

    @Test
    void forwardsBoundedQueryAndRendersJson() throws IOException {
        AtomicReference<String> project = new AtomicReference<>();
        AtomicReference<String> symbol = new AtomicReference<>();
        AtomicInteger limit = new AtomicInteger();
        ProjectSymbolQuery query = new EmptyProjectQuery() {
            @Override
            public List<UsageResult> findUsages(String projectId, String symbolId, int maximum) {
                project.set(projectId);
                symbol.set(symbolId);
                limit.set(maximum);
                return List.of(usage());
            }
        };

        StringBuilder output = new StringBuilder();
        int code = new FindUsagesCommand(query).run(
                new String[]{"project-1", "symbol-1", "--limit", "7", "--format", "json"},
                output,
                new StringBuilder()
        );

        assertEquals(0, code);
        assertEquals("project-1", project.get());
        assertEquals("symbol-1", symbol.get());
        assertEquals(7, limit.get());
        assertTrue(output.toString().contains("\"usages\":[{"));
    }

    @Test
    void validatesSyntaxBeforeQuerying() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        ProjectSymbolQuery query = new EmptyProjectQuery() {
            @Override
            public List<UsageResult> findUsages(String projectId, String symbolId, int limit) {
                calls.incrementAndGet();
                return List.of();
            }
        };
        StringBuilder error = new StringBuilder();

        int code = new FindUsagesCommand(query).run(
                new String[]{"project-1", "symbol-1", "--limit", "0"},
                new StringBuilder(),
                error
        );

        assertEquals(FindSymbolCommand.USAGE_ERROR, code);
        assertEquals(0, calls.get());
        assertTrue(error.toString().contains("limit must be between 1 and 1000"));
    }

    @Test
    void sanitizesExecutionFailure() throws IOException {
        ProjectSymbolQuery query = new EmptyProjectQuery() {
            @Override
            public List<UsageResult> findUsages(String projectId, String symbolId, int limit) {
                throw new IllegalStateException("snapshot\nunavailable");
            }
        };
        StringBuilder error = new StringBuilder();

        int code = new FindUsagesCommand(query).run(
                new String[]{"project-1", "symbol-1"},
                new StringBuilder(),
                error
        );

        assertEquals(FindSymbolCommand.EXECUTION_ERROR, code);
        assertEquals("error: find-usages failed: snapshot unavailable\n", error.toString());
    }

    private static UsageResult usage() {
        return new UsageResult(
                "occ-1", "project-1", "symbol-1",
                new SymbolLocation("file-1", 2, 0, 2, 4, PositionEncoding.UTF16_CODE_UNITS),
                Set.of(OccurrenceRole.REFERENCE), ResolutionStatus.RESOLVED,
                new Origin("fixture", "TEST", "1", "run", OriginType.OTHER)
        );
    }

    private abstract static class EmptyProjectQuery implements ProjectSymbolQuery {
        @Override
        public List<com.minos.query.SymbolResult> findSymbols(
                String projectId,
                com.minos.domain.SymbolSearchCriteria criteria
        ) {
            return List.of();
        }
    }
}
