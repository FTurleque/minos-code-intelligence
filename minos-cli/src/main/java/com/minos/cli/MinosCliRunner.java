package com.minos.cli;

import com.minos.application.MinosApplication;
import com.minos.integration.nexus.NexusExportService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Reusable local CLI execution surface, separated from the process/system launcher.
 *
 * <p>M15-S3 centralizes local composition in {@link MinosApplication}. M15-S4
 * will separately replace the temporary MCP -> CLI business routing.</p>
 */
public final class MinosCliRunner {

    public static final String HOME_ENVIRONMENT_VARIABLE = "MINOS_HOME";
    public static final String HOME_SYSTEM_PROPERTY = "minos.home";

    private MinosCliRunner() {
    }

    /** Compatibility entry point for callers that only have a MINOS home. */
    public static int run(
            Path home,
            String[] arguments,
            Appendable output,
            Appendable error
    ) throws IOException {
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(error, "error");
        if (isHelp(arguments)) {
            output.append(MinosCli.usage()).append('\n');
            return FindSymbolCommand.SUCCESS;
        }
        return run(MinosApplication.open(home), arguments, output, error);
    }

    /** Executes the CLI against an already-composed long-lived application. */
    public static int run(
            MinosApplication application,
            String[] arguments,
            Appendable output,
            Appendable error
    ) throws IOException {
        MinosApplication app = Objects.requireNonNull(application, "application");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(error, "error");

        if (isHelp(arguments)) {
            output.append(MinosCli.usage()).append('\n');
            return FindSymbolCommand.SUCCESS;
        }

        NexusExportCommand nexusExportCommand = new NexusExportCommand(projectRoot ->
                new NexusExportService(app.projectRegistry(), app.snapshotStore()).export(projectRoot));
        return new MinosCli(
                new LocalProjectSymbolQuery(app.projectRegistry(), app.snapshotStore()),
                new LocalProjectOperations(app),
                app.architectureQuery(),
                app.impactQuery(),
                nexusExportCommand,
                new LocalAutonomousIndexOperations(app),
                app.home()
        ).run(arguments, output, error);
    }

    public static Path resolveHome(Map<String, String> environment, Properties properties) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(properties, "properties");

        String property = properties.getProperty(HOME_SYSTEM_PROPERTY);
        if (property != null && !property.isBlank()) {
            return Path.of(property);
        }
        String environmentValue = environment.get(HOME_ENVIRONMENT_VARIABLE);
        if (environmentValue != null && !environmentValue.isBlank()) {
            return Path.of(environmentValue);
        }
        String userHome = properties.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            throw new IllegalStateException(
                    "neither minos.home, MINOS_HOME nor user.home defines a storage directory");
        }
        return Path.of(userHome).resolve(".minos");
    }

    private static boolean isHelp(String[] arguments) {
        return arguments.length == 1 && ("--help".equals(arguments[0]) || "-h".equals(arguments[0]));
    }
}
