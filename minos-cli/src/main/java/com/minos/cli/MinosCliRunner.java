package com.minos.cli;

import com.minos.application.LocalProjectOperations;
import com.minos.application.LocalProjectSymbolQuery;
import com.minos.application.ProjectOperations;
import com.minos.application.ProjectSymbolQuery;
import com.minos.application.MinosApplication;
import com.minos.application.MinosHome;
import com.minos.application.ProviderPlatformService;
import com.minos.architecture.ProjectArchitectureQuery;
import com.minos.impact.ProjectImpactQuery;
import com.minos.nexus.NexusExportService;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/** Reusable local CLI execution surface, separated from the process/system launcher. */
public final class MinosCliRunner {

    public static final String HOME_ENVIRONMENT_VARIABLE = MinosHome.ENVIRONMENT_VARIABLE;
    public static final String HOME_SYSTEM_PROPERTY = MinosHome.SYSTEM_PROPERTY;

    private static final String SEMANTIC_COMMAND = "semantic";
    private static final String HYBRID_COMMAND = "hybrid";
    private static final String MCP_COMMAND = "mcp";
    private static final String MCP_USAGE = """
            Usage: minos mcp

            Starts the MINOS MCP server on standard input/output. It takes no option.
            """.stripTrailing();

    /**
     * A CLI wired with collaborators that fail on first use. Its route table ({@link MinosCli#commandNames()})
     * is the list of the commands that answer {@code --help} before {@code MINOS_HOME} is opened; there is no
     * second list to keep in step.
     */
    private static final MinosCli STATELESS_HELP_CLI = statelessHelpCli();

    private MinosCliRunner() { }

    public static int run(Path home, String[] arguments, Appendable output, Appendable error) throws IOException {
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(error, "error");
        if (isStatelessHelpRequest(arguments)) return runStatelessHelp(arguments, output, error);
        if (isIdeHandshake(arguments)) return runIdeHandshake(arguments, output, error);
        try (MinosApplication application = MinosApplication.open(home)) {
            return run(application, arguments, output, error);
        }
    }

    public static int run(MinosApplication application, String[] arguments, Appendable output, Appendable error) throws IOException {
        MinosApplication app = Objects.requireNonNull(application, "application");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(error, "error");
        if (isStatelessHelpRequest(arguments)) return runStatelessHelp(arguments, output, error);
        if (isIdeHandshake(arguments)) return runIdeHandshake(arguments, output, error);
        if (isIdeIntelligenceRequest(arguments)) {
            return new IdeIntelligenceCommand(app).run(slice(arguments, 1), output, error);
        }
        if (isRetrievalStatusRequest(arguments)) {
            return new RetrievalStatusCommand(retrievalMode(arguments[0]), app.semanticIndexService())
                    .run(slice(arguments, 1), output, error);
        }

        NexusExportCommand nexusExportCommand = new NexusExportCommand(projectRoot ->
                new NexusExportService(app.projectRegistry(), app.snapshotStore()).export(projectRoot));
        LocalAutonomousIndexOperations autonomousIndex = new LocalAutonomousIndexOperations(app);
        MinosCli.Builder cli = MinosCli.builder(new LocalProjectSymbolQuery(app))
                .projectOperations(new LocalProjectOperations(app))
                .architectureQuery(app.architectureQuery())
                .impactQuery(app.impactQuery())
                .nexusExportCommand(nexusExportCommand)
                .autonomousOperations(autonomousIndex)
                .home(app.home())
                .providerPlatformService(ProviderPlatformService.defaults(app))
                .gitIntelligence(app.gitIntelligence())
                .remoteIndexOperations(new LocalRemoteIndexOperations(app))
                .runtimeIntelligenceService(app.runtimeIntelligenceService());
        app.hostedControlPlaneService().ifPresent(cli::hostedControlPlaneService);
        return cli.resumeStatus(autonomousIndex).build().run(arguments, output, error);
    }

    /**
     * A help request is a help token that is the last of at most three arguments, after a known command:
     * {@code minos <command> --help} and {@code minos <command> <operation-or-operand> --help}
     * (for instance {@code tools install --help}, {@code team audit --help}). It is answered before
     * {@code MINOS_HOME} is opened, whatever the command.
     */
    static boolean isStatelessHelpRequest(String[] arguments) {
        Objects.requireNonNull(arguments, "arguments");
        if (isHelp(arguments)) return true;
        if (arguments.length == 2 && isHelpToken(arguments[1])) {
            return isKnownCommand(arguments[0]);
        }
        return arguments.length == 3
                && isHelpToken(arguments[2])
                && isKnownCommand(arguments[0])
                && CliCommandSupport.isOperand(arguments[1]);
    }

    private static boolean isKnownCommand(String name) {
        return STATELESS_HELP_CLI.hasCommand(name) || isRetrievalCommand(name) || MCP_COMMAND.equals(name);
    }

    static int runStatelessHelp(String[] arguments, Appendable output, Appendable error) throws IOException {
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(error, "error");
        if (!isStatelessHelpRequest(arguments)) {
            throw new IllegalArgumentException("arguments are not a stateless CLI help request");
        }
        String command = arguments[0];
        if (isRetrievalCommand(command)) {
            output.append(RetrievalStatusCommand.usage(retrievalMode(command))).append('\n');
            return FindSymbolCommand.SUCCESS;
        }
        if (MCP_COMMAND.equals(command)) {
            output.append(MCP_USAGE).append('\n');
            return FindSymbolCommand.SUCCESS;
        }
        // project add|list|inspect --help keeps its own usage; any other operation shows the command's usage.
        boolean projectOperation = arguments.length == 3 && ProjectCommand.NAME.equals(command)
                && Set.of("add", "list", "inspect").contains(arguments[1]);
        if (arguments.length == 3 && !projectOperation) {
            return STATELESS_HELP_CLI.run(new String[]{command, arguments[2]}, output, error);
        }
        return STATELESS_HELP_CLI.run(arguments, output, error);
    }

    /**
     * Names of the commands of the CLI that answer {@code --help} without opening {@code MINOS_HOME}
     * ({@code mcp}, a launcher command that is not part of the CLI usage, answers it too).
     */
    static Set<String> statelessHelpCommands() {
        Set<String> names = new java.util.LinkedHashSet<>(STATELESS_HELP_CLI.commandNames());
        names.add(SEMANTIC_COMMAND);
        names.add(HYBRID_COMMAND);
        return java.util.Collections.unmodifiableSet(names);
    }

    static boolean isIdeHandshake(String[] arguments) {
        Objects.requireNonNull(arguments, "arguments");
        return arguments.length >= 2
                && IdeCommand.NAME.equals(arguments[0])
                && "handshake".equals(arguments[1]);
    }

    static boolean isIdeIntelligenceRequest(String[] arguments) {
        Objects.requireNonNull(arguments, "arguments");
        return arguments.length >= 2
                && IdeCommand.NAME.equals(arguments[0])
                && !"handshake".equals(arguments[1]);
    }

    static int runIdeHandshake(String[] arguments, Appendable output, Appendable error) throws IOException {
        if (!isIdeHandshake(arguments)) {
            throw new IllegalArgumentException("arguments are not an IDE handshake request");
        }
        return new IdeCommand().run(slice(arguments, 1), output, error);
    }

    public static Path resolveHome(Map<String, String> environment, Properties properties) {
        return MinosHome.resolve(environment, properties);
    }

    private static boolean isRetrievalStatusRequest(String[] arguments) {
        return arguments.length >= 1 && isRetrievalCommand(arguments[0]);
    }

    private static boolean isRetrievalCommand(String command) {
        return SEMANTIC_COMMAND.equals(command) || HYBRID_COMMAND.equals(command);
    }

    private static RetrievalStatusCommand.Mode retrievalMode(String command) {
        return switch (command) {
            case SEMANTIC_COMMAND -> RetrievalStatusCommand.Mode.SEMANTIC;
            case HYBRID_COMMAND -> RetrievalStatusCommand.Mode.HYBRID;
            default -> throw new IllegalArgumentException("unsupported retrieval command: " + command);
        };
    }

    private static MinosCli statelessHelpCli() {
        ProjectSymbolQuery symbolQuery = unused(ProjectSymbolQuery.class);
        ProjectOperations projectOperations = unused(ProjectOperations.class);
        AutonomousIndexOperations autonomousOperations = unused(AutonomousIndexOperations.class);
        return MinosCli.builder(symbolQuery)
                .projectOperations(projectOperations)
                .architectureQuery(unused(ProjectArchitectureQuery.class))
                .impactQuery(unused(ProjectImpactQuery.class))
                .nexusExportCommand(new NexusExportCommand(projectRoot -> {
                    throw new IllegalStateException("stateless help attempted NEXUS export");
                }))
                .autonomousOperations(autonomousOperations)
                .home(Path.of("."))
                .build();
    }

    private static <T> T unused(Class<T> contract) {
        Object proxy = Proxy.newProxyInstance(
                contract.getClassLoader(),
                new Class<?>[]{contract},
                (instance, method, arguments) -> {
                    throw new IllegalStateException(
                            "stateless help attempted to invoke " + contract.getSimpleName() + "." + method.getName());
                }
        );
        return contract.cast(proxy);
    }

    private static boolean isHelp(String[] arguments) {
        return arguments.length == 1 && isHelpToken(arguments[0]);
    }

    private static boolean isHelpToken(String argument) {
        return CliCommandSupport.isHelp(argument);
    }

    private static String[] slice(String[] values, int from) {
        return java.util.Arrays.copyOfRange(values, from, values.length);
    }
}
