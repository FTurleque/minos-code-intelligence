package com.minos.cli;

import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolSearchCriteria;
import com.minos.query.SymbolResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FindSymbolCommandTest {

    @Test
    void forwardsStructuredCriteriaAndRendersJson() throws IOException {
        AtomicReference<String> projectId = new AtomicReference<>();
        AtomicReference<SymbolSearchCriteria> criteria = new AtomicReference<>();
        FindSymbolCommand command = new FindSymbolCommand((project, searchCriteria) -> {
            projectId.set(project);
            criteria.set(searchCriteria);
            return List.of(greetingResult());
        });
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();

        int exitCode = command.run(new String[]{
                "project-1",
                "Greeting",
                "--qualified-name", "com.minos.GreetingService",
                "--kind", "class",
                "--module", "app",
                "--limit", "7",
                "--format", "JSON"
        }, output, error);

        assertEquals(FindSymbolCommand.SUCCESS, exitCode);
        assertEquals("project-1", projectId.get());
        assertEquals("Greeting", criteria.get().text());
        assertEquals("com.minos.GreetingService", criteria.get().qualifiedName());
        assertEquals(SymbolKind.CLASS, criteria.get().kind());
        assertEquals("app", criteria.get().moduleId());
        assertEquals(7, criteria.get().limit());
        assertTrue(output.toString().startsWith("{\"count\":1,\"symbols\":[{"));
        assertTrue(output.toString().contains("\"name\":\"GreetingService\""));
        assertTrue(output.toString().contains("\"qualifiedName\":\"com.minos.GreetingService\""));
        assertEquals("", error.toString());
    }

    @Test
    void usesBoundedTextDefaults() throws IOException {
        AtomicReference<SymbolSearchCriteria> criteria = new AtomicReference<>();
        FindSymbolCommand command = new FindSymbolCommand((projectId, searchCriteria) -> {
            criteria.set(searchCriteria);
            return List.of();
        });
        StringBuilder output = new StringBuilder();

        int exitCode = command.run(
                new String[]{"project-1", "Greeting"},
                output,
                new StringBuilder()
        );

        assertEquals(FindSymbolCommand.SUCCESS, exitCode);
        assertEquals(FindSymbolCommand.DEFAULT_LIMIT, criteria.get().limit());
        assertEquals("symbols: 0\n", output.toString());
    }

    @Test
    void helpDoesNotQueryTheProject() throws IOException {
        AtomicInteger invocations = new AtomicInteger();
        FindSymbolCommand command = new FindSymbolCommand((projectId, criteria) -> {
            invocations.incrementAndGet();
            return List.of();
        });
        StringBuilder output = new StringBuilder();

        int exitCode = command.run(
                new String[]{"--help"},
                output,
                new StringBuilder()
        );

        assertEquals(FindSymbolCommand.SUCCESS, exitCode);
        assertEquals(0, invocations.get());
        assertEquals(FindSymbolCommand.usage() + "\n", output.toString());
    }

    @Test
    void rejectsInvalidSyntaxBeforeQuerying() throws IOException {
        AtomicInteger invocations = new AtomicInteger();
        FindSymbolCommand command = new FindSymbolCommand((projectId, criteria) -> {
            invocations.incrementAndGet();
            return List.of();
        });
        List<InvalidArguments> cases = List.of(
                new InvalidArguments(new String[]{}, "expected <project> and <symbol>"),
                new InvalidArguments(new String[]{"project-1"}, "expected <project> and <symbol>"),
                new InvalidArguments(
                        new String[]{"project-1", "Greeting", "--unknown", "value"},
                        "unknown option: --unknown"
                ),
                new InvalidArguments(
                        new String[]{"project-1", "Greeting", "--limit", "0"},
                        "limit must be between 1 and 1000"
                ),
                new InvalidArguments(
                        new String[]{"project-1", "Greeting", "--limit", "many"},
                        "invalid limit: many"
                ),
                new InvalidArguments(
                        new String[]{"project-1", "Greeting", "--kind", "service"},
                        "unsupported symbol kind: service"
                ),
                new InvalidArguments(
                        new String[]{"project-1", "Greeting", "--format", "yaml"},
                        "unsupported output format: yaml"
                ),
                new InvalidArguments(
                        new String[]{"project-1", "Greeting", "--module"},
                        "missing value for --module"
                ),
                new InvalidArguments(
                        new String[]{"project-1", "Greeting", null},
                        "argument at index 2 must not be null"
                ),
                new InvalidArguments(
                        new String[]{
                                "project-1", "Greeting",
                                "--limit", "2",
                                "--limit", "3"
                        },
                        "duplicate option: --limit"
                )
        );

        for (InvalidArguments invalid : cases) {
            StringBuilder error = new StringBuilder();
            int exitCode = command.run(invalid.arguments(), new StringBuilder(), error);

            assertEquals(FindSymbolCommand.USAGE_ERROR, exitCode);
            assertTrue(error.toString().contains("error: " + invalid.message()), error.toString());
            assertTrue(error.toString().contains(FindSymbolCommand.usage()), error.toString());
        }
        assertEquals(0, invocations.get());
    }

    @Test
    void mapsProjectLoadingFailureToExecutionError() throws IOException {
        FindSymbolCommand command = new FindSymbolCommand((projectId, criteria) -> {
            throw new IllegalStateException("active snapshot\nis unavailable");
        });
        StringBuilder error = new StringBuilder();

        int exitCode = command.run(
                new String[]{"project-1", "Greeting"},
                new StringBuilder(),
                error
        );

        assertEquals(FindSymbolCommand.EXECUTION_ERROR, exitCode);
        assertEquals(
                "error: find-symbol failed: active snapshot is unavailable\n",
                error.toString()
        );
    }

    private record InvalidArguments(String[] arguments, String message) {
    }

    private static SymbolResult greetingResult() {
        return new SymbolResult(
                "symbol-greeting-service",
                "project-1|java|CLASS|com.minos.GreetingService",
                SymbolIdentityQuality.STRUCTURAL_FALLBACK,
                "project-1",
                "app",
                "file-greeting-service",
                SymbolKind.CLASS,
                "GreetingService",
                "com.minos.GreetingService",
                null,
                "java",
                null,
                ResolutionStatus.RESOLVED,
                new Origin(
                        "fixture-provider",
                        "TEST",
                        null,
                        "run-1",
                        OriginType.OTHER
                ),
                false,
                false
        );
    }
}
