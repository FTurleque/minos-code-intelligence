package com.minos.cli;

import com.minos.application.MinosApplication;
import com.minos.application.MinosHome;
import com.minos.application.ProviderPlatformService;
import com.minos.architecture.ProjectArchitectureQuery;
import com.minos.impact.ProjectImpactQuery;
import com.minos.integration.nexus.NexusExportService;

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

    private static final Set<String> STATELESS_HELP_COMMANDS = Set.of(
            ProjectCommand.NAME,
            "inspect",
            "index-status",
            IndexCommand.NAME,
            ImportScipCommand.NAME,
            ToolsCommand.NAME,
            ProviderCommand.NAME,
            SearchCodeCommand.NAME,
            FindSymbolCommand.NAME,
            GetSourceCommand.NAME,
            FindUsagesCommand.NAME,
            "find-implementations",
            "find-callers",
            "find-callees",
            "dependencies",
            "dependents",
            "related-tests",
            ArchitectureCommand.NAME,
            ImpactCommand.NAME,
            IdeCommand.NAME,
            GitActivityCommand.NAME,
            NexusExportCommand.NAME
    );

    private MinosCliRunner() { }

    public static int run(Path home, String[] arguments, Appendable output, Appendable error) throws IOException {
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(error, "error");
        if (isStatelessHelpRequest(arguments)) return runStatelessHelp(arguments, output, error);
        if (isIdeHandshake(arguments)) return new IdeCommand().run(slice(arguments, 1), output, error);
        return run(MinosApplication.open(home), arguments, output, error);
    }

    public static int run(MinosApplication application, String[] arguments, Appendable output, Appendable error) throws IOException {
        MinosApplication app = Objects.requireNonNull(application, "application");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(error, "error");
        if (isStatelessHelpRequest(arguments)) return runStatelessHelp(arguments, output, error);
        if (isIdeHandshake(arguments)) return new IdeCommand().run(slice(arguments, 1), output, error);

        NexusExportCommand nexusExportCommand = new NexusExportCommand(projectRoot ->
                new NexusExportService(app.projectRegistry(), app.snapshotStore()).export(projectRoot));
        return new MinosCli(
                new LocalProjectSymbolQuery(app),
                new LocalProjectOperations(app),
                app.architectureQuery(),
                app.impactQuery(),
                nexusExportCommand,
                new LocalAutonomousIndexOperations(app),
                app.home(),
                ProviderPlatformService.defaults(app),
                app.gitIntelligence()
        ).run(arguments, output, error);
    }

    static boolean isStatelessHelpRequest(String[] arguments) {
        Objects.requireNonNull(arguments, "arguments");
        if (isHelp(arguments)) return true;
        if (arguments.length == 2 && isHelpToken(arguments[1])) return STATELESS_HELP_COMMANDS.contains(arguments[0]);
        return arguments.length == 3
                && ProjectCommand.NAME.equals(arguments[0])
                && Set.of("add", "list", "inspect").contains(arguments[1])
                && isHelpToken(arguments[2]);
    }

    static int runStatelessHelp(String[] arguments, Appendable output, Appendable error) throws IOException {
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(error, "error");
        if (!isStatelessHelpRequest(arguments)) {
            throw new IllegalArgumentException("arguments are not a stateless CLI help request");
        }
        if (arguments.length == 2 && ProviderCommand.NAME.equals(arguments[0]) && isHelpToken(arguments[1])) {
            output.append(ProviderCommand.usage()).append('\n');
            return FindSymbolCommand.SUCCESS;
        }
        if (arguments.length == 2 && GitActivityCommand.NAME.equals(arguments[0]) && isHelpToken(arguments[1])) {
            output.append(GitActivityCommand.usage()).append('\n');
            return FindSymbolCommand.SUCCESS;
        }
        return statelessHelpCli().run(arguments, output, error);
    }

    public static Path resolveHome(Map<String, String> environment, Properties properties) {
        return MinosHome.resolve(environment, properties);
    }

    private static MinosCli statelessHelpCli() {
        ProjectSymbolQuery symbolQuery = unused(ProjectSymbolQuery.class);
        ProjectOperations projectOperations = unused(ProjectOperations.class);
        AutonomousIndexOperations autonomousOperations = unused(AutonomousIndexOperations.class);
        return new MinosCli(
                symbolQuery,
                projectOperations,
                unused(ProjectArchitectureQuery.class),
                unused(ProjectImpactQuery.class),
                new NexusExportCommand(projectRoot -> {
                    throw new IllegalStateException("stateless help attempted NEXUS export");
                }),
                autonomousOperations,
                Path.of(".")
        );
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
        return "--help".equals(argument) || "-h".equals(argument);
    }

    private static boolean isIdeHandshake(String[] arguments) {
        return arguments.length >= 2
                && IdeCommand.NAME.equals(arguments[0])
                && "handshake".equals(arguments[1]);
    }

    private static String[] slice(String[] values, int from) {
        return java.util.Arrays.copyOfRange(values, from, values.length);
    }
}
