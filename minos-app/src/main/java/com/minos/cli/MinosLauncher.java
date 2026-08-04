package com.minos.cli;

import com.minos.application.MinosApplication;
import com.minos.runtime.MinosVersion;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/** Point d'entrée système de MINOS. */
public final class MinosLauncher {

    public static final String VERSION = MinosVersion.current();
    public static final String HOME_ENVIRONMENT_VARIABLE = MinosCliRunner.HOME_ENVIRONMENT_VARIABLE;
    public static final String HOME_SYSTEM_PROPERTY = MinosCliRunner.HOME_SYSTEM_PROPERTY;

    private MinosLauncher() {
    }

    public static void main(String[] arguments) {
        int exitCode;
        try {
            if (arguments.length == 1 && "--version".equals(arguments[0])) {
                System.out.println("MINOS " + VERSION);
                exitCode = FindSymbolCommand.SUCCESS;
            } else if (isHelp(arguments)) {
                System.out.println(MinosCli.usage());
                exitCode = FindSymbolCommand.SUCCESS;
            } else if (MinosCliRunner.isStatelessHelpRequest(arguments)) {
                exitCode = MinosCliRunner.runStatelessHelp(arguments, System.out, System.err);
            } else if (MinosCliRunner.isIdeHandshake(arguments)) {
                exitCode = MinosCliRunner.runIdeHandshake(arguments, System.out, System.err);
            } else {
                Path home = resolveHome(System.getenv(), System.getProperties());
                if (arguments.length == 1 && "mcp".equals(arguments[0])) {
                    // M29: route before opening MinosApplication. A Docker MCP session must not
                    // touch or create native registry/snapshot/vector-store state as a side effect.
                    exitCode = new McpBackendRouter().run(home);
                } else {
                    MinosApplication application = MinosApplication.open(home);
                    exitCode = run(application, arguments, System.out, System.err);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            exitCode = FindSymbolCommand.EXECUTION_ERROR;
        } catch (Exception exception) {
            System.err.println("error: MINOS bootstrap failed: " + failureMessage(exception));
            exitCode = FindSymbolCommand.EXECUTION_ERROR;
        }
        System.exit(exitCode);
    }

    public static int run(
            Path home,
            String[] arguments,
            Appendable output,
            Appendable error
    ) throws IOException {
        return MinosCliRunner.run(home, arguments, output, error);
    }

    public static int run(
            MinosApplication application,
            String[] arguments,
            Appendable output,
            Appendable error
    ) throws IOException {
        return MinosCliRunner.run(application, arguments, output, error);
    }

    static Path resolveHome(Map<String, String> environment, Properties properties) {
        return MinosCliRunner.resolveHome(environment, properties);
    }

    private static boolean isHelp(String[] arguments) {
        return arguments.length == 1 && ("--help".equals(arguments[0]) || "-h".equals(arguments[0]));
    }

    private static String failureMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message.replace('\r', ' ').replace('\n', ' ');
    }
}
