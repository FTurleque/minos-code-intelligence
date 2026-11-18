package com.minos.cli.command;

import com.minos.cli.support.CliExitCodes;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A command asked directly for its help prints its usage and exits with {@link CliExitCodes#SUCCESS}, before it
 * needs any operation; a command given an unknown action exits with {@link CliExitCodes#USAGE_ERROR}.
 */
class CommandHelpExitCodesTest {

    private final StringBuilder output = new StringBuilder();
    private final StringBuilder error = new StringBuilder();

    @Test
    void ideHelpSucceedsAndAnUnknownActionIsAUsageError() throws IOException {
        assertEquals(CliExitCodes.SUCCESS, new IdeCommand().run(new String[] {"--help"}, output, error));
        assertTrue(output.toString().contains("handshake"), output.toString());

        assertEquals(CliExitCodes.USAGE_ERROR, new IdeCommand().run(new String[] {"unknown"}, output, error));
        assertTrue(error.toString().contains("expected `ide handshake`"), error.toString());
    }

    @Test
    void retrievalStatusHelpSucceedsWithAndWithoutTheAction() throws IOException {
        RetrievalStatusCommand command = new RetrievalStatusCommand(RetrievalStatusCommand.Mode.SEMANTIC,
                reference -> {
                    throw new AssertionError("help reads no status");
                });

        assertEquals(CliExitCodes.SUCCESS, command.run(new String[] {"--help"}, output, error));
        assertEquals(CliExitCodes.SUCCESS, command.run(new String[] {"status", "--help"}, output, error));
        assertEquals("", error.toString());
    }

    @Test
    void nexusExportHelpSucceedsWithoutExporting() throws IOException {
        NexusExportCommand command = new NexusExportCommand(root -> {
            throw new AssertionError("help exports nothing");
        });

        assertEquals(CliExitCodes.SUCCESS, command.run(new String[] {"--help"}, output, error));
        assertEquals("", error.toString());
    }
}
