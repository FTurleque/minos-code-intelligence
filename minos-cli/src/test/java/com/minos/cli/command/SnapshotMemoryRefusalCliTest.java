package com.minos.cli.command;

import com.minos.diagnostics.SnapshotMemoryExhaustedException;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AUD-PERF-15 — a query whose snapshot view is refused for lack of memory prints the public message of the refusal on
 * the error output and ends with the existing execution-error code (1): no new exit code, nothing on the output.
 */
class SnapshotMemoryRefusalCliTest {

    private static final String MESSAGE = SnapshotMemoryExhaustedException.MESSAGE_PREFIX
            + " now: 600 bytes could not be reserved within 30 seconds while other snapshot views use the 500-byte"
            + " budget; retry later, raise the maximum heap or MINOS_VIEW_HEAP_PERCENT";

    @Test
    void aMemoryRefusalIsPrintedOnTheErrorOutputWithExitCodeOne() throws IOException {
        FindSymbolCommand command = new FindSymbolCommand((projectId, criteria) -> {
            throw new SnapshotMemoryExhaustedException(MESSAGE);
        });
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();

        int exitCode = command.run(new String[]{"project-1", "Greeting"}, output, error);

        assertEquals(1, exitCode);
        assertEquals(FindSymbolCommand.EXECUTION_ERROR, exitCode);
        assertEquals("", output.toString());
        assertEquals("error: find-symbol failed: " + MESSAGE + "\n", error.toString());
    }
}
