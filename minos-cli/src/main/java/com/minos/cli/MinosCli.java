package com.minos.cli;

import com.minos.application.ProjectOperations;
import com.minos.application.ProjectSymbolQuery;
import com.minos.application.ProviderPlatformService;
import com.minos.architecture.ProjectArchitectureQuery;
import com.minos.application.dynamic.RuntimeIntelligenceService;
import com.minos.git.GitIntelligence;
import com.minos.hosted.HostedControlPlaneService;
import com.minos.impact.ProjectImpactQuery;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

/** Dispatcher stable des commandes CLI MINOS. */
public final class MinosCli {

    private static final String USAGE = """
            Usage: minos <command> [arguments]

            Project and index:
              project add       Register a local project
              project list      List registered projects
              project inspect   Inspect discovery and active index state
              inspect           Alias for project inspect
              index             Discover, select and execute providers automatically
              import-scip       Import an explicit SCIP artifact for diagnostics/fallback
              index-status      Show active snapshot and known index metadata
              remote            Materialize/index an immutable GitHub or GitLab revision

            Runtime:
              runtime           Import/query partial runtime observations and hot paths
              doctor            Diagnose MINOS and provider prerequisites
              tools list        List managed providers
              tools install     Install/bootstrap a managed provider
              tools verify      Verify managed provider runtimes
              providers         Show provider capabilities, limitations and runtime state

            Code intelligence:
              search             Build a bounded code context
              find-symbol        Find normalized symbols
              get-source         Explicitly retrieve a complete local source file
              find-usages        Find resolved usages of a normalized symbol
              find-implementations  Find implementation-navigation relations
              find-callers       Find incoming CALLS relations when available
              find-callees       Find outgoing CALLS relations when available
              dependencies       Find outgoing DEPENDS_ON relations
              dependents         Find incoming DEPENDS_ON relations
              related-tests      Find explained tests related to a production symbol
              architecture       Inspect project or module architecture intelligence
              impact             Analyze potential impact from a symbol
              semantic status    Show semantic vector-index readiness
              hybrid status      Show hybrid readiness and structured fallback mode

            Integration:
              ide handshake      Negotiate the versioned external IDE protocol
              git-activity       Show factual Git file/zone activity for a project
              nexus-export       Export the active normalized snapshot as NEXUS contract JSON

            Team / hosted (opt-in):
              team               Manage tenant-scoped shared workspaces and governance

            Exit codes:
              0  success
              1  execution failure / doctor action required
              2  usage error

            Run `minos <command> --help` for command options.
            """.stripTrailing();

    /**
     * One route per top-level command: its usage and its handler. Every command has a route even when
     * its collaborator is not configured (the handler then answers "not configured"), so that the
     * names, the usages and {@code --help} do not depend on the wiring. Tests and the stateless help of
     * {@link MinosCliRunner} enumerate this table instead of a copied list.
     */
    private final Map<String, Route> routes = new LinkedHashMap<>();

    private record Route(String usage, Handler handler) { }

    @FunctionalInterface
    private interface Handler {
        int run(String[] arguments, Appendable output, Appendable error) throws IOException;
    }

    @FunctionalInterface
    private interface CommandHandler<C> {
        int run(C command, String[] arguments, Appendable output, Appendable error) throws IOException;
    }

    /**
     * Seul point d'entrée (ADR 0045). Seules les requêtes de symboles sont obligatoires ; un collaborateur
     * non fourni laisse sa commande « not configured in this CLI bootstrap ».
     */
    public static Builder builder(ProjectSymbolQuery symbolQuery) {
        return new Builder(symbolQuery);
    }

    /** Câblage complet ; {@code resumeStatus} alimente `index-status` avec le run proposé à la reprise (ADR 0039 §6). */
    private MinosCli(Builder builder) {
        ProjectSymbolQuery symbolQuery = builder.symbolQuery;
        ProjectOperations projectOperations = builder.projectOperations;
        ProjectArchitectureQuery architectureQuery = builder.architectureQuery;
        ProjectImpactQuery impactQuery = builder.impactQuery;
        NexusExportCommand nexusExportCommand = builder.nexusExportCommand;
        AutonomousIndexOperations autonomousOperations = builder.autonomousOperations;
        Path home = builder.home;
        ProviderPlatformService providerPlatformService = builder.providerPlatformService;
        GitIntelligence gitIntelligenceService = builder.gitIntelligence;
        RemoteIndexOperations remoteIndexOperations = builder.remoteIndexOperations;
        RuntimeIntelligenceService runtimeIntelligenceService = builder.runtimeIntelligenceService;
        HostedControlPlaneService hostedControlPlaneService = builder.hostedControlPlaneService;
        IndexResumeStatusSource resumeStatus = builder.resumeStatus;
        register(IdeCommand.NAME, IdeCommand.usage() + "\n\n" + IdeIntelligenceCommand.usage(),
                new IdeCommand(), IdeCommand::run);
        ProjectCommand projectCommand = projectOperations == null ? null : new ProjectCommand(projectOperations,
                resumeStatus == null ? projectId -> java.util.Optional.empty() : resumeStatus);
        register(ProjectCommand.NAME, ProjectCommand.usage(), projectCommand, ProjectCommand::run);
        register("inspect", ProjectCommand.inspectUsage(), projectCommand, ProjectCommand::runInspectAlias);
        register(IndexCommand.NAME, IndexCommand.usage(),
                projectOperations == null ? null : new IndexCommand(projectOperations, autonomousOperations),
                IndexCommand::run);
        register(ImportScipCommand.NAME, ImportScipCommand.usage(),
                projectOperations == null ? null : new ImportScipCommand(projectOperations), ImportScipCommand::run);
        register("index-status", ProjectCommand.indexStatusUsage(), projectCommand, ProjectCommand::runIndexStatus);
        register(ToolsCommand.NAME, ToolsCommand.usage(),
                autonomousOperations == null ? null : new ToolsCommand(autonomousOperations), ToolsCommand::run);
        register(DoctorCommand.NAME, DoctorCommand.usage(),
                autonomousOperations == null || home == null ? null : new DoctorCommand(home, autonomousOperations),
                DoctorCommand::run);
        register(ProviderCommand.NAME, ProviderCommand.usage(),
                providerPlatformService == null ? null : new ProviderCommand(providerPlatformService),
                ProviderCommand::run);
        register(ArchitectureCommand.NAME, ArchitectureCommand.usage(),
                architectureQuery == null ? null : new ArchitectureCommand(architectureQuery), ArchitectureCommand::run);
        register(ImpactCommand.NAME, ImpactCommand.usage(),
                impactQuery == null ? null : new ImpactCommand(impactQuery), ImpactCommand::run);
        register(GitActivityCommand.NAME, GitActivityCommand.usage(),
                projectOperations == null || gitIntelligenceService == null
                        ? null : new GitActivityCommand(projectOperations, gitIntelligenceService),
                GitActivityCommand::run);
        register(NexusExportCommand.NAME, NexusExportCommand.usage(), nexusExportCommand, NexusExportCommand::run);
        register(RemoteIndexCommand.NAME, RemoteIndexCommand.usage(),
                remoteIndexOperations == null ? null : new RemoteIndexCommand(remoteIndexOperations),
                RemoteIndexCommand::run);
        register(RuntimeCommand.NAME, RuntimeCommand.usage(),
                runtimeIntelligenceService == null ? null : new RuntimeCommand(runtimeIntelligenceService),
                RuntimeCommand::run);
        register(TeamCommand.NAME, TeamCommand.usage(),
                hostedControlPlaneService == null ? null : new TeamCommand(
                        hostedControlPlaneService, () -> System.getenv(TeamCommand.TOKEN_ENVIRONMENT_VARIABLE)),
                TeamCommand::run);
        register(FindSymbolCommand.NAME, FindSymbolCommand.usage(), new FindSymbolCommand(symbolQuery),
                FindSymbolCommand::run);
        register(SearchCodeCommand.NAME, SearchCodeCommand.usage(), new SearchCodeCommand(symbolQuery),
                SearchCodeCommand::run);
        register(GetSourceCommand.NAME, GetSourceCommand.usage(), new GetSourceCommand(symbolQuery),
                GetSourceCommand::run);
        register(FindUsagesCommand.NAME, FindUsagesCommand.usage(), new FindUsagesCommand(symbolQuery),
                FindUsagesCommand::run);
        for (RelationshipCommand.Operation operation : RelationshipCommand.Operation.values()) {
            register(operation.commandName(), RelationshipCommand.usage(operation),
                    new RelationshipCommand(operation, symbolQuery), RelationshipCommand::run);
        }
    }

    private <C> void register(String name, String usage, C command, CommandHandler<C> handler) {
        routes.put(name, new Route(usage, command == null
                ? (arguments, output, error) -> unavailable(name, error)
                : (arguments, output, error) -> handler.run(command, arguments, output, error)));
    }

    /** The names of every top-level command, in declaration order. */
    Set<String> commandNames() {
        return Collections.unmodifiableSet(routes.keySet());
    }

    /** Whether {@code name} is a top-level command of this CLI. */
    boolean hasCommand(String name) {
        return routes.containsKey(name);
    }

    public int run(String[] arguments, Appendable output, Appendable error) throws IOException {
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(error, "error");
        if (arguments.length == 1 && CliCommandSupport.isHelp(arguments[0])) {
            output.append(USAGE).append('\n');
            return FindSymbolCommand.SUCCESS;
        }
        if (arguments.length == 0) {
            error.append("error: command is required\n").append(USAGE).append('\n');
            return FindSymbolCommand.USAGE_ERROR;
        }
        String command = arguments[0];
        Route route = routes.get(command);
        if (route == null) {
            error.append("error: unknown command: ").append(command).append('\n');
            error.append(USAGE).append('\n');
            return FindSymbolCommand.USAGE_ERROR;
        }
        String[] commandArguments = Arrays.copyOfRange(arguments, 1, arguments.length);
        // --help is answered from the route itself: it never depends on the wiring nor touches any service.
        if (commandArguments.length == 1 && CliCommandSupport.isHelp(commandArguments[0])) {
            output.append(route.usage()).append('\n');
            return FindSymbolCommand.SUCCESS;
        }
        return route.handler().run(commandArguments, output, error);
    }

    public static String usage() { return USAGE; }

    private static int unavailable(String command, Appendable error) throws IOException {
        error.append("error: ").append(command).append(" is not configured in this CLI bootstrap\n");
        return FindSymbolCommand.EXECUTION_ERROR;
    }

    /** Collaborateurs de {@link MinosCli} ; chaque mutateur refuse {@code null}, un collaborateur absent n'est simplement pas fourni. */
    public static final class Builder {
        private final ProjectSymbolQuery symbolQuery;
        private ProjectOperations projectOperations;
        private ProjectArchitectureQuery architectureQuery;
        private ProjectImpactQuery impactQuery;
        private NexusExportCommand nexusExportCommand;
        private AutonomousIndexOperations autonomousOperations;
        private Path home;
        private ProviderPlatformService providerPlatformService;
        private GitIntelligence gitIntelligence;
        private RemoteIndexOperations remoteIndexOperations;
        private RuntimeIntelligenceService runtimeIntelligenceService;
        private HostedControlPlaneService hostedControlPlaneService;
        private IndexResumeStatusSource resumeStatus;

        private Builder(ProjectSymbolQuery symbolQuery) {
            this.symbolQuery = Objects.requireNonNull(symbolQuery, "symbolQuery");
        }

        public Builder projectOperations(ProjectOperations value) {
            this.projectOperations = Objects.requireNonNull(value);
            return this;
        }

        public Builder architectureQuery(ProjectArchitectureQuery value) {
            this.architectureQuery = Objects.requireNonNull(value);
            return this;
        }

        public Builder impactQuery(ProjectImpactQuery value) {
            this.impactQuery = Objects.requireNonNull(value);
            return this;
        }

        Builder nexusExportCommand(NexusExportCommand value) {
            this.nexusExportCommand = Objects.requireNonNull(value);
            return this;
        }

        Builder autonomousOperations(AutonomousIndexOperations value) {
            this.autonomousOperations = Objects.requireNonNull(value);
            return this;
        }

        Builder home(Path value) {
            this.home = Objects.requireNonNull(value);
            return this;
        }

        Builder providerPlatformService(ProviderPlatformService value) {
            this.providerPlatformService = Objects.requireNonNull(value);
            return this;
        }

        Builder gitIntelligence(GitIntelligence value) {
            this.gitIntelligence = Objects.requireNonNull(value);
            return this;
        }

        Builder remoteIndexOperations(RemoteIndexOperations value) {
            this.remoteIndexOperations = Objects.requireNonNull(value);
            return this;
        }

        Builder runtimeIntelligenceService(RuntimeIntelligenceService value) {
            this.runtimeIntelligenceService = Objects.requireNonNull(value);
            return this;
        }

        Builder hostedControlPlaneService(HostedControlPlaneService value) {
            this.hostedControlPlaneService = Objects.requireNonNull(value);
            return this;
        }

        Builder resumeStatus(IndexResumeStatusSource value) {
            this.resumeStatus = Objects.requireNonNull(value);
            return this;
        }

        public MinosCli build() {
            return new MinosCli(this);
        }
    }
}
