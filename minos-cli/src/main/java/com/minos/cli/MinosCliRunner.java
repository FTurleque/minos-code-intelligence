package com.minos.cli;

import com.minos.application.LocalProjectOperations;
import com.minos.application.LocalProjectSymbolQuery;
import com.minos.application.ProjectOperations;
import com.minos.application.ProjectSymbolQuery;
import com.minos.application.MinosApplication;
import com.minos.application.MinosHome;
import com.minos.application.ProviderPlatformService;
import com.minos.architecture.ProjectArchitectureQuery;
import com.minos.git.GitIntelligence;
import com.minos.impact.ProjectImpactQuery;
import com.minos.nexus.NexusExportService;

import java.io.IOException;
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
    private static final String LONG_HELP = "--help";
    private static final String MCP_USAGE = """
            Usage: minos mcp

            Starts the MINOS MCP server on standard input/output. It takes no option.
            """.stripTrailing();

    /**
     * The CLI wired on an application that is never opened: building it opens and constructs nothing (every
     * collaborator is deferred, see {@link LazyApplication}). Its route table ({@link MinosCli#commandNames()}) is
     * the list of the commands that answer {@code --help} before {@code MINOS_HOME} is opened; there is no second
     * list to keep in step.
     */
    private static final MinosCli STATELESS_HELP_CLI = cli(LazyApplication.opening(Path.of("."), () -> {
        throw new IOException("stateless help never opens MINOS_HOME");
    }));

    private MinosCliRunner() { }

    public static int run(Path home, String[] arguments, Appendable output, Appendable error) throws IOException {
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(error, "error");
        try (LazyApplication application = LazyApplication.opening(home, () -> MinosApplication.open(home))) {
            return run(application, arguments, output, error);
        } catch (LazyApplication.OpenFailure failure) {
            throw failure.rethrow();
        }
    }

    public static int run(MinosApplication application, String[] arguments, Appendable output, Appendable error) throws IOException {
        Objects.requireNonNull(application, "application");
        return run(LazyApplication.of(application), arguments, output, error);
    }

    /**
     * Runs a command line on an application that is opened only when the command needs it. The command analyses
     * its arguments first, with no service: a usage error, a help request and every command that needs no
     * application leave {@code MINOS_HOME} untouched, and a command constructs only what it calls.
     */
    static int run(LazyApplication application, String[] arguments, Appendable output, Appendable error) throws IOException {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(error, "error");
        if (isStatelessHelpRequest(arguments)) return runStatelessHelp(arguments, output, error);
        if (isIdeHandshake(arguments)) return runIdeHandshake(arguments, output, error);
        if (isIdeIntelligenceRequest(arguments)) {
            return new IdeIntelligenceCommand(application::get).run(slice(arguments, 1), output, error);
        }
        if (isRetrievalStatusRequest(arguments)) {
            return new RetrievalStatusCommand(retrievalMode(arguments[0]),
                    project -> application.get().semanticIndexService().status(project))
                    .run(slice(arguments, 1), output, error);
        }
        return cli(application).run(arguments, output, error);
    }

    /** The one place where the commands are wired: every collaborator is a deferred handle on {@code application}. */
    private static MinosCli cli(LazyApplication application) {
        LazyApplication.Deferred<LocalAutonomousIndexOperations> autonomousIndex =
                application.deferred(LocalAutonomousIndexOperations::new);
        MinosCli.Builder cli = MinosCli.builder(
                        LazyApplication.lazy(ProjectSymbolQuery.class, application.deferred(LocalProjectSymbolQuery::new)))
                .projectOperations(
                        LazyApplication.lazy(ProjectOperations.class, application.deferred(LocalProjectOperations::new)))
                .architectureQuery(LazyApplication.lazy(ProjectArchitectureQuery.class,
                        application.deferred(MinosApplication::architectureQuery)))
                .impactQuery(LazyApplication.lazy(ProjectImpactQuery.class,
                        application.deferred(MinosApplication::impactQuery)))
                .nexusExportCommand(new NexusExportCommand(projectRoot -> {
                    MinosApplication opened = application.get();
                    return new NexusExportService(opened.projectRegistry(), opened.snapshotStore()).export(projectRoot);
                }))
                .autonomousOperations(LazyApplication.lazy(AutonomousIndexOperations.class, autonomousIndex))
                .home(application.home())
                .providerPlatformServiceSupplier(application.deferred(ProviderPlatformService::defaults))
                .gitIntelligence(LazyApplication.lazy(GitIntelligence.class,
                        application.deferred(MinosApplication::gitIntelligence)))
                .remoteIndexOperations(LazyApplication.lazy(RemoteIndexOperations.class,
                        application.deferred(LocalRemoteIndexOperations::new)))
                .runtimeIntelligenceServiceSupplier(application.deferred(MinosApplication::runtimeIntelligenceService))
                .hostedControlPlaneServiceSupplier(application.deferred(opened -> opened.hostedControlPlaneService()
                        .orElseThrow(MinosCli.ServiceNotConfigured::new)));
        return cli.resumeStatus(projectId -> autonomousIndex.get().resumableRun(projectId)).build();
    }

    /**
     * A help request is a help token after a known command: {@code minos <command> --help}, a short help token that is
     * the last of at most three arguments ({@code tools install -h}, {@code team audit --help}), or a long
     * {@code --help} at <em>any</em> position ({@code find-symbol p S --limit 5 --help}). It is answered before
     * {@code MINOS_HOME} is opened, whatever the command. {@code --help} cannot be the datum of a valid command
     * line, since no operand or value may start with {@code --}; {@code -h} can ({@code --name -h}), which is why
     * it keeps its positions.
     */
    static boolean isStatelessHelpRequest(String[] arguments) {
        Objects.requireNonNull(arguments, "arguments");
        if (isHelp(arguments)) return true;
        if (arguments.length < 2 || !isKnownCommand(arguments[0])) return false;
        if (arguments.length == 2) return isHelpToken(arguments[1]);
        if (arguments.length == 3 && isHelpToken(arguments[2]) && CliCommandSupport.isOperand(arguments[1])) {
            return true;
        }
        for (int index = 1; index < arguments.length; index++) {
            if (LONG_HELP.equals(arguments[index])) return true;
        }
        return false;
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
        // project add|list|inspect --help keeps its own usage; any other form shows the command's usage.
        boolean projectOperation = arguments.length == 3 && ProjectCommand.NAME.equals(command)
                && Set.of("add", "list", "inspect").contains(arguments[1]) && isHelpToken(arguments[2]);
        if (arguments.length <= 2 || projectOperation) {
            return STATELESS_HELP_CLI.run(arguments, output, error);
        }
        return STATELESS_HELP_CLI.run(new String[]{command, LONG_HELP}, output, error);
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
