package com.minos.cli;

import com.minos.application.MinosApplication;
import com.minos.diagnostics.PublicErrorMessages;
import com.minos.runtime.MinosVersion;

import java.io.IOException;
import java.io.PrintStream;
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

    /** Ouvre l'application d'un MINOS_HOME ; en production {@link MinosApplication#open}. */
    @FunctionalInterface
    interface ApplicationOpener {
        MinosApplication open(Path home) throws IOException;
    }

    /**
     * Exécute une ligne de commande sur une application ouverte à la demande ; en production {@link #run}. La
     * commande analyse ses arguments avant d'avoir besoin de l'application : une erreur d'usage, une aide ou une
     * commande sans état n'ouvrent jamais {@code MINOS_HOME}.
     */
    @FunctionalInterface
    interface CommandRunner {
        int run(LazyApplication application, String[] arguments, Appendable output, Appendable error)
                throws IOException;
    }

    public static void main(String[] arguments) {
        System.exit(launch(arguments, System.getenv(), System.getProperties(), System.out, System.err,
                MinosApplication::open, MinosLauncher::run));
    }

    /**
     * Le processus, sans {@code System.exit} : rend le code de sortie. L'application qu'une commande ouvre
     * appartient à ce lanceur, qui la ferme sur tous les chemins de sortie (succès, code d'erreur, exception) ;
     * une commande qui n'en a pas besoin n'en ouvre aucune.
     */
    static int launch(
            String[] arguments,
            Map<String, String> environment,
            Properties properties,
            PrintStream out,
            PrintStream err,
            ApplicationOpener opener,
            CommandRunner runner
    ) {
        int exitCode;
        try {
            if (arguments.length == 1 && "--version".equals(arguments[0])) {
                out.println("MINOS " + VERSION);
                exitCode = FindSymbolCommand.SUCCESS;
            } else if (isHelp(arguments)) {
                out.println(MinosCli.usage());
                exitCode = FindSymbolCommand.SUCCESS;
            } else if (MinosCliRunner.isStatelessHelpRequest(arguments)) {
                exitCode = MinosCliRunner.runStatelessHelp(arguments, out, err);
            } else if (MinosCliRunner.isIdeHandshake(arguments)) {
                exitCode = MinosCliRunner.runIdeHandshake(arguments, out, err);
            } else {
                Path home = resolveHome(environment, properties);
                if (arguments.length == 1 && "mcp".equals(arguments[0])) {
                    // Route before opening MinosApplication so Docker MCP does not touch native
                    // business stores. The registered entry point (minos-app's McpBackendRouter)
                    // still validates/hardens MINOS_HOME before it reads backend.properties or
                    // performs any Docker side effect. ADR 0044: minos-cli cannot depend on
                    // minos-mcp, so the route is an SPI resolved in MINOS's own class loader.
                    exitCode = McpLaunchRoutes.resolve().run(home);
                } else {
                    try (LazyApplication application = LazyApplication.opening(home, () -> opener.open(home))) {
                        exitCode = runner.run(application, arguments, out, err);
                    }
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            exitCode = FindSymbolCommand.EXECUTION_ERROR;
        } catch (LazyApplication.OpenFailure openFailure) {
            err.println("error: MINOS bootstrap failed: " + failureMessage(openFailure.failure()));
            exitCode = FindSymbolCommand.EXECUTION_ERROR;
        } catch (Exception exception) {
            err.println("error: MINOS bootstrap failed: " + failureMessage(exception));
            exitCode = FindSymbolCommand.EXECUTION_ERROR;
        }
        return exitCode;
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

    static int run(
            LazyApplication application,
            String[] arguments,
            Appendable output,
            Appendable error
    ) throws IOException {
        return MinosCliRunner.run(application, arguments, output, error);
    }

    static Path resolveHome(Map<String, String> environment, Properties properties) {
        return MinosCliRunner.resolveHome(environment, properties);
    }

    static String failureMessage(Exception exception) {
        return PublicErrorMessages.sanitize(exception.getMessage(), exception.getClass().getSimpleName());
    }

    private static boolean isHelp(String[] arguments) {
        return arguments.length == 1 && CliCommandSupport.isHelp(arguments[0]);
    }
}
