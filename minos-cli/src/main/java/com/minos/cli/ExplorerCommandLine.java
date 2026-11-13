package com.minos.cli;

import com.minos.cli.command.FindSymbolCommand;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The command line of {@code minos explorer}: its usage, its options and its exit codes. The surface itself is
 * not in this module: a valid command is handed to the registered {@link ExplorerLaunchRoute}.
 */
final class ExplorerCommandLine {

    static final String NAME = "explorer";

    static final String USAGE = """
            Usage: minos explorer serve [--port <n>]

            Serves the local, read-only explorer of the semantic index, until Ctrl+C.
            It listens on the loopback only (127.0.0.1), requires the session token carried by the
            address it prints on standard error, and never writes, indexes or synchronizes anything.
            It refuses to start when the team mode is enabled.

            Options:
              --port <n>   Port to listen on, 0 to 65535. Default: 0, a free port chosen by the system.

            Exit codes:
              0  stopped on request
              1  the port could not be bound
              2  usage error, or the surface refuses to start here
            """.stripTrailing();

    static final String MISSING = "MINOS explorer entry point is missing: the minos-app module must be on the"
            + " classpath (no " + ExplorerLaunchRoute.class.getName() + " service is registered)";

    private static final String SERVE = "serve";
    private static final String PORT = "--port";
    private static final int MAX_PORT = 65_535;

    private ExplorerCommandLine() {
    }

    static boolean matches(String[] arguments) {
        return arguments.length >= 1 && NAME.equals(arguments[0]);
    }

    /** Runs {@code minos explorer ...} through the route registered by the assembly. */
    static int run(String[] arguments, Path home, Appendable error) throws Exception {
        return run(arguments, home, error, ExplorerCommandLine::registeredRoute);
    }

    /**
     * Parses the command, then hands it to the route. A usage error is reported with the usage and never
     * resolves the route: nothing is opened and nothing listens.
     */
    static int run(String[] arguments, Path home, Appendable error, Supplier<ExplorerLaunchRoute> route)
            throws Exception {
        if (arguments.length < 2 || !SERVE.equals(arguments[1])) {
            return usageError(error, arguments.length < 2 ? "missing operation: serve"
                    : "unknown explorer operation (expected: serve)");
        }
        int port = 0;
        boolean portGiven = false;
        for (int index = 2; index < arguments.length; index++) {
            if (!PORT.equals(arguments[index])) {
                return usageError(error, "unknown option for explorer serve (expected: --port <n>)");
            }
            if (portGiven) return usageError(error, "--port is given twice");
            if (index + 1 == arguments.length) return usageError(error, "--port needs a value");
            Integer parsed = port(arguments[++index]);
            if (parsed == null) return usageError(error, "--port must be an integer between 0 and " + MAX_PORT);
            port = parsed;
            portGiven = true;
        }
        return route.get().run(home, port, error);
    }

    private static Integer port(String value) {
        if (!value.matches("[0-9]{1,5}")) return null;
        int parsed = Integer.parseInt(value);
        return parsed > MAX_PORT ? null : parsed;
    }

    private static int usageError(Appendable error, String message) throws IOException {
        error.append("error: ").append(message).append(System.lineSeparator())
                .append(USAGE).append(System.lineSeparator());
        return FindSymbolCommand.USAGE_ERROR;
    }

    /** The single explorer entry point visible from the class loader of MINOS itself. */
    private static ExplorerLaunchRoute registeredRoute() {
        List<ServiceLoader.Provider<ExplorerLaunchRoute>> providers = ServiceLoader
                .load(ExplorerLaunchRoute.class, ExplorerLaunchRoute.class.getClassLoader()).stream().toList();
        if (providers.isEmpty()) throw new IllegalStateException(MISSING);
        if (providers.size() > 1) {
            throw new IllegalStateException("MINOS refuses to choose an explorer entry point: " + providers.size()
                    + " " + ExplorerLaunchRoute.class.getSimpleName() + " services are registered ("
                    + providers.stream().map(provider -> provider.type().getName()).sorted()
                    .collect(Collectors.joining(", ")) + "); exactly one minos-app module must be on the classpath");
        }
        return providers.getFirst().get();
    }
}
