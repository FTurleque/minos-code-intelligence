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
import java.util.function.Supplier;

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
              3  partial result: valid for what was read, unreadable registry entries were counted and ignored

            Run `minos <command> --help` for command options.
            """.stripTrailing();

    /**
     * Une route par commande de premier niveau : son usage et son gestionnaire. Toute commande a une route,
     * même quand son collaborateur n'est pas câblé (le gestionnaire répond alors « not configured »), de sorte
     * que les noms, les usages et {@code --help} ne dépendent pas du câblage. Les tests et l'aide sans état de
     * {@link MinosCliRunner} énumèrent cette table au lieu d'une liste recopiée.
     */
    private final Map<String, Route> routes = new LinkedHashMap<>();

    private record Route(String usage, Handler handler) { }

    @FunctionalInterface
    private interface Handler {
        int run(String[] arguments, Appendable output, Appendable error) throws IOException;
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
        Supplier<ProviderPlatformService> providerPlatformService = builder.providerPlatformService;
        GitIntelligence gitIntelligenceService = builder.gitIntelligence;
        RemoteIndexOperations remoteIndexOperations = builder.remoteIndexOperations;
        Supplier<RuntimeIntelligenceService> runtimeIntelligenceService = builder.runtimeIntelligenceService;
        Supplier<HostedControlPlaneService> hostedControlPlaneService = builder.hostedControlPlaneService;
        IndexResumeStatusSource resumeStatus = builder.resumeStatus;
        IdeCommand ideCommand = new IdeCommand();
        ProjectCommand projectCommand = projectOperations == null ? null : new ProjectCommand(projectOperations,
                resumeStatus == null ? projectId -> java.util.Optional.empty() : resumeStatus);
        IndexCommand indexCommand = projectOperations == null ? null
                : new IndexCommand(projectOperations, autonomousOperations);
        ImportScipCommand importScipCommand = projectOperations == null ? null : new ImportScipCommand(projectOperations);
        ToolsCommand toolsCommand = autonomousOperations == null ? null : new ToolsCommand(autonomousOperations);
        DoctorCommand doctorCommand = autonomousOperations == null || home == null
                ? null : new DoctorCommand(home, autonomousOperations);
        ProviderCommand providerCommand = providerPlatformService == null
                ? null : new ProviderCommand(providerPlatformService);
        ArchitectureCommand architectureCommand = architectureQuery == null
                ? null : new ArchitectureCommand(architectureQuery);
        ImpactCommand impactCommand = impactQuery == null ? null : new ImpactCommand(impactQuery);
        GitActivityCommand gitActivityCommand = projectOperations == null || gitIntelligenceService == null
                ? null : new GitActivityCommand(projectOperations, gitIntelligenceService);
        RemoteIndexCommand remoteIndexCommand = remoteIndexOperations == null
                ? null : new RemoteIndexCommand(remoteIndexOperations);
        RuntimeCommand runtimeCommand = runtimeIntelligenceService == null
                ? null : new RuntimeCommand(runtimeIntelligenceService);
        TeamCommand teamCommand = hostedControlPlaneService == null ? null : new TeamCommand(
                hostedControlPlaneService, () -> System.getenv(TeamCommand.TOKEN_ENVIRONMENT_VARIABLE));
        FindSymbolCommand findSymbolCommand = new FindSymbolCommand(symbolQuery);
        SearchCodeCommand searchCodeCommand = new SearchCodeCommand(symbolQuery);
        GetSourceCommand getSourceCommand = new GetSourceCommand(symbolQuery);
        FindUsagesCommand findUsagesCommand = new FindUsagesCommand(symbolQuery);

        register(IdeCommand.NAME, IdeCommand.usage() + "\n\n" + IdeIntelligenceCommand.usage(), true,
                (arguments, output, error) -> ideCommand.run(arguments, output, error));
        register(ProjectCommand.NAME, ProjectCommand.usage(), projectCommand != null,
                (arguments, output, error) -> projectCommand.run(arguments, output, error));
        register("inspect", ProjectCommand.inspectUsage(), projectCommand != null,
                (arguments, output, error) -> projectCommand.runInspectAlias(arguments, output, error));
        register(IndexCommand.NAME, IndexCommand.usage(), indexCommand != null,
                (arguments, output, error) -> indexCommand.run(arguments, output, error));
        register(ImportScipCommand.NAME, ImportScipCommand.usage(), importScipCommand != null,
                (arguments, output, error) -> importScipCommand.run(arguments, output, error));
        register("index-status", ProjectCommand.indexStatusUsage(), projectCommand != null,
                (arguments, output, error) -> projectCommand.runIndexStatus(arguments, output, error));
        register(ToolsCommand.NAME, ToolsCommand.usage(), toolsCommand != null,
                (arguments, output, error) -> toolsCommand.run(arguments, output, error));
        register(DoctorCommand.NAME, DoctorCommand.usage(), doctorCommand != null,
                (arguments, output, error) -> doctorCommand.run(arguments, output, error));
        register(ProviderCommand.NAME, ProviderCommand.usage(), providerCommand != null,
                (arguments, output, error) -> providerCommand.run(arguments, output, error));
        register(ArchitectureCommand.NAME, ArchitectureCommand.usage(), architectureCommand != null,
                (arguments, output, error) -> architectureCommand.run(arguments, output, error));
        register(ImpactCommand.NAME, ImpactCommand.usage(), impactCommand != null,
                (arguments, output, error) -> impactCommand.run(arguments, output, error));
        register(GitActivityCommand.NAME, GitActivityCommand.usage(), gitActivityCommand != null,
                (arguments, output, error) -> gitActivityCommand.run(arguments, output, error));
        register(NexusExportCommand.NAME, NexusExportCommand.usage(), nexusExportCommand != null,
                (arguments, output, error) -> nexusExportCommand.run(arguments, output, error));
        register(RemoteIndexCommand.NAME, RemoteIndexCommand.usage(), remoteIndexCommand != null,
                (arguments, output, error) -> remoteIndexCommand.run(arguments, output, error));
        register(RuntimeCommand.NAME, RuntimeCommand.usage(), runtimeCommand != null,
                (arguments, output, error) -> runtimeCommand.run(arguments, output, error));
        register(TeamCommand.NAME, TeamCommand.usage(), teamCommand != null,
                (arguments, output, error) -> teamCommand.run(arguments, output, error));
        register(FindSymbolCommand.NAME, FindSymbolCommand.usage(), true,
                (arguments, output, error) -> findSymbolCommand.run(arguments, output, error));
        register(SearchCodeCommand.NAME, SearchCodeCommand.usage(), true,
                (arguments, output, error) -> searchCodeCommand.run(arguments, output, error));
        register(GetSourceCommand.NAME, GetSourceCommand.usage(), true,
                (arguments, output, error) -> getSourceCommand.run(arguments, output, error));
        register(FindUsagesCommand.NAME, FindUsagesCommand.usage(), true,
                (arguments, output, error) -> findUsagesCommand.run(arguments, output, error));
        for (RelationshipCommand.Operation operation : RelationshipCommand.Operation.values()) {
            RelationshipCommand relationshipCommand = new RelationshipCommand(operation, symbolQuery);
            register(operation.commandName(), RelationshipCommand.usage(operation), true,
                    (arguments, output, error) -> relationshipCommand.run(arguments, output, error));
        }
    }

    /**
     * Ajoute la route d'une commande ; une commande dont le collaborateur n'est pas câblé, ou dont le service différé
     * s'avère absent à son premier appel ({@link ServiceNotConfigured}), répond « not configured ».
     */
    private void register(String name, String usage, boolean configured, Handler handler) {
        routes.put(name, new Route(usage, configured ? (arguments, output, error) -> {
            try {
                return handler.run(arguments, output, error);
            } catch (ServiceNotConfigured notConfigured) {
                return unavailable(name, error);
            }
        } : (arguments, output, error) -> unavailable(name, error)));
    }

    /**
     * Levée par le fournisseur d'un service différé qui n'existe pas dans cette configuration (le mode hébergé
     * désactivé, par exemple). Non vérifiée et distincte de {@link IllegalStateException}, que des commandes interceptent.
     */
    static final class ServiceNotConfigured extends RuntimeException {
        ServiceNotConfigured() {
            super("service not configured", null, false, false);
        }
    }

    /** Les noms de toutes les commandes de premier niveau, dans l'ordre de déclaration. */
    Set<String> commandNames() {
        return Collections.unmodifiableSet(routes.keySet());
    }

    /** Indique si {@code name} est une commande de premier niveau de ce CLI. */
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
        // --help est servi par la route elle-même : il ne dépend pas du câblage et ne touche aucun service.
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
        private Supplier<ProviderPlatformService> providerPlatformService;
        private GitIntelligence gitIntelligence;
        private RemoteIndexOperations remoteIndexOperations;
        private Supplier<RuntimeIntelligenceService> runtimeIntelligenceService;
        private Supplier<HostedControlPlaneService> hostedControlPlaneService;
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
            Objects.requireNonNull(value);
            return providerPlatformServiceSupplier(() -> value);
        }

        /** Service construit à son premier appel : la commande analyse ses arguments avant de le demander. */
        Builder providerPlatformServiceSupplier(Supplier<ProviderPlatformService> value) {
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
            Objects.requireNonNull(value);
            return runtimeIntelligenceServiceSupplier(() -> value);
        }

        /** Service construit à son premier appel : la commande analyse ses arguments avant de le demander. */
        Builder runtimeIntelligenceServiceSupplier(Supplier<RuntimeIntelligenceService> value) {
            this.runtimeIntelligenceService = Objects.requireNonNull(value);
            return this;
        }

        Builder hostedControlPlaneService(HostedControlPlaneService value) {
            Objects.requireNonNull(value);
            return hostedControlPlaneServiceSupplier(() -> value);
        }

        /**
         * Service construit à son premier appel ; le fournisseur lève {@link ServiceNotConfigured} quand le mode
         * hébergé n'est pas activé, et la commande répond alors « not configured in this CLI bootstrap ».
         */
        Builder hostedControlPlaneServiceSupplier(Supplier<HostedControlPlaneService> value) {
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
