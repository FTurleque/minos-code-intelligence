package com.minos.mcp;

import com.minos.application.MinosApplication;
import com.minos.cli.MinosCliRunner;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;

/**
 * Temporary M15-S3 bridge: MCP still maps to CLI commands, but all calls share
 * one long-lived MinosApplication. M15-S4 removes the CLI routing itself.
 */
final class MinosApplicationCommandExecutor implements MinosMcpTools.CommandExecutor {

    private final MinosApplication application;

    MinosApplicationCommandExecutor(MinosApplication application) {
        this.application = Objects.requireNonNull(application, "application");
    }

    @Override
    public MinosMcpTools.CommandResult execute(List<String> arguments) {
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();
        try {
            int exitCode = MinosCliRunner.run(
                    application,
                    arguments.toArray(String[]::new),
                    output,
                    error
            );
            return new MinosMcpTools.CommandResult(exitCode, output.toString(), error.toString());
        } catch (java.io.IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
