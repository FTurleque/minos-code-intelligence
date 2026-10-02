package com.minos.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinosCliTest {

    @Test
    void dispatchesFindSymbolWithoutOwningProjectBootstrap() throws IOException {
        AtomicReference<String> projectId = new AtomicReference<>();
        MinosCli cli = MinosCli.builder((project, criteria) -> {
            projectId.set(project);
            return List.of();
        }).build();
        StringBuilder output = new StringBuilder();

        int exitCode = cli.run(new String[]{
                "find-symbol", "project-1", "Greeting", "--format", "json"
        }, output, new StringBuilder());

        assertEquals(FindSymbolCommand.SUCCESS, exitCode);
        assertEquals("project-1", projectId.get());
        assertEquals("{\"count\":0,\"symbols\":[]}\n", output.toString());
    }

    @Test
    void rendersRootHelpWithoutDispatching() throws IOException {
        MinosCli cli = MinosCli.builder((project, criteria) -> {
            throw new AssertionError("query must not be invoked");
        }).build();
        StringBuilder output = new StringBuilder();

        int exitCode = cli.run(new String[]{"--help"}, output, new StringBuilder());

        assertEquals(FindSymbolCommand.SUCCESS, exitCode);
        assertEquals(MinosCli.usage() + "\n", output.toString());
    }

    @Test
    void rejectsMissingAndUnknownCommands() throws IOException {
        MinosCli cli = MinosCli.builder((project, criteria) -> List.of()).build();

        StringBuilder missingError = new StringBuilder();
        int missingExitCode = cli.run(new String[]{}, new StringBuilder(), missingError);
        assertEquals(FindSymbolCommand.USAGE_ERROR, missingExitCode);
        assertTrue(missingError.toString().startsWith("error: command is required\n"));

        StringBuilder unknownError = new StringBuilder();
        int unknownExitCode = cli.run(
                new String[]{"unknown"},
                new StringBuilder(),
                unknownError
        );
        assertEquals(FindSymbolCommand.USAGE_ERROR, unknownExitCode);
        assertTrue(unknownError.toString().startsWith("error: unknown command: unknown\n"));
    }
}
