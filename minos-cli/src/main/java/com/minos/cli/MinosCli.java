package com.minos.cli;

import com.minos.application.ProviderPlatformService;
import com.minos.architecture.ProjectArchitectureQuery;
import com.minos.dynamic.RuntimeIntelligenceService;
import com.minos.git.GitIntelligenceService;
import com.minos.hosted.HostedControlPlaneService;
import com.minos.impact.ProjectImpactQuery;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
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

    private final FindSymbolCommand findSymbolCommand;
    private final SearchCodeCommand searchCodeCommand;
    private final GetSourceCommand getSourceCommand;
    private final FindUsagesCommand findUsagesCommand;
    private final java.util.Map<String, RelationshipCommand> relationshipCommands;
    private final ProjectCommand projectCommand;
    private final IndexCommand indexCommand;
    private final ImportScipCommand importScipCommand;
    private final ToolsCommand toolsCommand;
    private final DoctorCommand doctorCommand;
    private final ProviderCommand providerCommand;
    private final ArchitectureCommand architectureCommand;
    private final ImpactCommand impactCommand;
    private final IdeCommand ideCommand;
    private final GitActivityCommand gitActivityCommand;
    private final NexusExportCommand nexusExportCommand;
    private final RemoteIndexCommand remoteIndexCommand;
    private final RuntimeCommand runtimeCommand;
    private final TeamCommand teamCommand;

    public MinosCli(ProjectSymbolQuery symbolQuery) {
        this(symbolQuery, null, null, null, null, null, null, null);
    }

    public MinosCli(
            ProjectSymbolQuery symbolQuery,
            ProjectOperations projectOperations,
            ProjectArchitectureQuery architectureQuery,
            ProjectImpactQuery impactQuery
    ) {
        this(symbolQuery, projectOperations, architectureQuery, impactQuery, null, null, null, null);
    }

    MinosCli(
            ProjectSymbolQuery symbolQuery,
            ProjectOperations projectOperations,
            ProjectArchitectureQuery architectureQuery,
            ProjectImpactQuery impactQuery,
            NexusExportCommand nexusExportCommand
    ) {
        this(symbolQuery, projectOperations, architectureQuery, impactQuery, nexusExportCommand, null, null, null);
    }

    MinosCli(
            ProjectSymbolQuery symbolQuery,
            ProjectOperations projectOperations,
            ProjectArchitectureQuery architectureQuery,
            ProjectImpactQuery impactQuery,
            NexusExportCommand nexusExportCommand,
            AutonomousIndexOperations autonomousOperations,
            Path home
    ) {
        this(symbolQuery, projectOperations, architectureQuery, impactQuery,
                nexusExportCommand, autonomousOperations, home, null);
    }

    MinosCli(
            ProjectSymbolQuery symbolQuery,
            ProjectOperations projectOperations,
            ProjectArchitectureQuery architectureQuery,
            ProjectImpactQuery impactQuery,
            NexusExportCommand nexusExportCommand,
            AutonomousIndexOperations autonomousOperations,
            Path home,
            ProviderPlatformService providerPlatformService
    ) {
        this(symbolQuery, projectOperations, architectureQuery, impactQuery, nexusExportCommand,
                autonomousOperations, home, providerPlatformService, null);
    }

    MinosCli(
            ProjectSymbolQuery symbolQuery,
            ProjectOperations projectOperations,
            ProjectArchitectureQuery architectureQuery,
            ProjectImpactQuery impactQuery,
            NexusExportCommand nexusExportCommand,
            AutonomousIndexOperations autonomousOperations,
            Path home,
            ProviderPlatformService providerPlatformService,
            GitIntelligenceService gitIntelligenceService
    ) {
        this(symbolQuery, projectOperations, architectureQuery, impactQuery, nexusExportCommand,
                autonomousOperations, home, providerPlatformService, gitIntelligenceService, null);
    }

    MinosCli(
            ProjectSymbolQuery symbolQuery,
            ProjectOperations projectOperations,
            ProjectArchitectureQuery architectureQuery,
            ProjectImpactQuery impactQuery,
            NexusExportCommand nexusExportCommand,
            AutonomousIndexOperations autonomousOperations,
            Path home,
            ProviderPlatformService providerPlatformService,
            GitIntelligenceService gitIntelligenceService,
            RemoteIndexOperations remoteIndexOperations
    ) {
        this(symbolQuery, projectOperations, architectureQuery, impactQuery, nexusExportCommand,
                autonomousOperations, home, providerPlatformService, gitIntelligenceService,
                remoteIndexOperations, null);
    }

    MinosCli(
            ProjectSymbolQuery symbolQuery,
            ProjectOperations projectOperations,
            ProjectArchitectureQuery architectureQuery,
            ProjectImpactQuery impactQuery,
            NexusExportCommand nexusExportCommand,
            AutonomousIndexOperations autonomousOperations,
            Path home,
            ProviderPlatformService providerPlatformService,
            GitIntelligenceService gitIntelligenceService,
            RemoteIndexOperations remoteIndexOperations,
            RuntimeIntelligenceService runtimeIntelligenceService
    ) {
        this(symbolQuery, projectOperations, architectureQuery, impactQuery, nexusExportCommand,
                autonomousOperations, home, providerPlatformService, gitIntelligenceService,
                remoteIndexOperations, runtimeIntelligenceService, null);
    }

    MinosCli(
            ProjectSymbolQuery symbolQuery,
            ProjectOperations projectOperations,
            ProjectArchitectureQuery architectureQuery,
            ProjectImpactQuery impactQuery,
            NexusExportCommand nexusExportCommand,
            AutonomousIndexOperations autonomousOperations,
            Path home,
            ProviderPlatformService providerPlatformService,
            GitIntelligenceService gitIntelligenceService,
            RemoteIndexOperations remoteIndexOperations,
            RuntimeIntelligenceService runtimeIntelligenceService,
            HostedControlPlaneService hostedControlPlaneService
    ) {
        Objects.requireNonNull(symbolQuery, "symbolQuery");
        this.findSymbolCommand = new FindSymbolCommand(symbolQuery);
        this.searchCodeCommand = new SearchCodeCommand(symbolQuery);
        this.getSourceCommand = new GetSourceCommand(symbolQuery);
        this.findUsagesCommand = new FindUsagesCommand(symbolQuery);
        java.util.Map<String, RelationshipCommand> commands = new java.util.LinkedHashMap<>();
        for (RelationshipCommand.Operation operation : RelationshipCommand.Operation.values()) {
            commands.put(operation.commandName(), new RelationshipCommand(operation, symbolQuery));
        }
        this.relationshipCommands = java.util.Map.copyOf(commands);
        this.projectCommand = projectOperations == null ? null : new ProjectCommand(projectOperations);
        this.indexCommand = projectOperations == null ? null : new IndexCommand(projectOperations, autonomousOperations);
        this.importScipCommand = projectOperations == null ? null : new ImportScipCommand(projectOperations);
        this.toolsCommand = autonomousOperations == null ? null : new ToolsCommand(autonomousOperations);
        this.doctorCommand = autonomousOperations == null || home == null ? null : new DoctorCommand(home, autonomousOperations);
        this.providerCommand = providerPlatformService == null ? null : new ProviderCommand(providerPlatformService);
        this.architectureCommand = architectureQuery == null ? null : new ArchitectureCommand(architectureQuery);
        this.impactCommand = impactQuery == null ? null : new ImpactCommand(impactQuery);
        this.ideCommand = new IdeCommand();
        this.gitActivityCommand = projectOperations == null || gitIntelligenceService == null
                ? null
                : new GitActivityCommand(projectOperations, gitIntelligenceService);
        this.nexusExportCommand = nexusExportCommand;
        this.remoteIndexCommand = remoteIndexOperations == null ? null : new RemoteIndexCommand(remoteIndexOperations);
        this.runtimeCommand = runtimeIntelligenceService == null ? null : new RuntimeCommand(runtimeIntelligenceService);
        this.teamCommand = hostedControlPlaneService == null ? null : new TeamCommand(
                hostedControlPlaneService, () -> System.getenv(TeamCommand.TOKEN_ENVIRONMENT_VARIABLE));
    }

    public int run(String[] arguments, Appendable output, Appendable error) throws IOException {
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(error, "error");
        if (arguments.length == 1 && ("--help".equals(arguments[0]) || "-h".equals(arguments[0]))) {
            output.append(USAGE).append('\n');
            return FindSymbolCommand.SUCCESS;
        }
        if (arguments.length == 0) {
            error.append("error: command is required\n").append(USAGE).append('\n');
            return FindSymbolCommand.USAGE_ERROR;
        }
        String command = arguments[0];
        String[] commandArguments = Arrays.copyOfRange(arguments, 1, arguments.length);
        if (IdeCommand.NAME.equals(command)) {
            return ideCommand.run(commandArguments, output, error);
        }
        if (ProjectCommand.NAME.equals(command)) {
            return projectCommand == null ? unavailable(command, error) : projectCommand.run(commandArguments, output, error);
        }
        if ("inspect".equals(command)) {
            return projectCommand == null ? unavailable(command, error) : projectCommand.runInspectAlias(commandArguments, output, error);
        }
        if (IndexCommand.NAME.equals(command)) {
            return indexCommand == null ? unavailable(command, error) : indexCommand.run(commandArguments, output, error);
        }
        if (ImportScipCommand.NAME.equals(command)) {
            return importScipCommand == null ? unavailable(command, error) : importScipCommand.run(commandArguments, output, error);
        }
        if ("index-status".equals(command)) {
            return projectCommand == null ? unavailable(command, error) : projectCommand.runIndexStatus(commandArguments, output, error);
        }
        if (ToolsCommand.NAME.equals(command)) {
            return toolsCommand == null ? unavailable(command, error) : toolsCommand.run(commandArguments, output, error);
        }
        if (DoctorCommand.NAME.equals(command)) {
            return doctorCommand == null ? unavailable(command, error) : doctorCommand.run(commandArguments, output, error);
        }
        if (ProviderCommand.NAME.equals(command)) {
            return providerCommand == null ? unavailable(command, error) : providerCommand.run(commandArguments, output, error);
        }
        if (ArchitectureCommand.NAME.equals(command)) {
            return architectureCommand == null ? unavailable(command, error) : architectureCommand.run(commandArguments, output, error);
        }
        if (ImpactCommand.NAME.equals(command)) {
            return impactCommand == null ? unavailable(command, error) : impactCommand.run(commandArguments, output, error);
        }
        if (GitActivityCommand.NAME.equals(command)) {
            return gitActivityCommand == null ? unavailable(command, error) : gitActivityCommand.run(commandArguments, output, error);
        }
        if (NexusExportCommand.NAME.equals(command)) {
            return nexusExportCommand == null ? unavailable(command, error) : nexusExportCommand.run(commandArguments, output, error);
        }
        if (RemoteIndexCommand.NAME.equals(command)) {
            return remoteIndexCommand == null ? unavailable(command, error) : remoteIndexCommand.run(commandArguments, output, error);
        }
        if (RuntimeCommand.NAME.equals(command)) {
            return runtimeCommand == null ? unavailable(command, error) : runtimeCommand.run(commandArguments, output, error);
        }
        if (TeamCommand.NAME.equals(command)) {
            return teamCommand == null ? unavailable(command, error) : teamCommand.run(commandArguments, output, error);
        }
        if (FindSymbolCommand.NAME.equals(command)) return findSymbolCommand.run(commandArguments, output, error);
        if (SearchCodeCommand.NAME.equals(command)) return searchCodeCommand.run(commandArguments, output, error);
        if (GetSourceCommand.NAME.equals(command)) return getSourceCommand.run(commandArguments, output, error);
        if (FindUsagesCommand.NAME.equals(command)) return findUsagesCommand.run(commandArguments, output, error);
        RelationshipCommand relationshipCommand = relationshipCommands.get(command);
        if (relationshipCommand != null) return relationshipCommand.run(commandArguments, output, error);
        error.append("error: unknown command: ").append(command).append('\n');
        error.append(USAGE).append('\n');
        return FindSymbolCommand.USAGE_ERROR;
    }

    public static String usage() { return USAGE; }

    private static int unavailable(String command, Appendable error) throws IOException {
        error.append("error: ").append(command).append(" is not configured in this CLI bootstrap\n");
        return FindSymbolCommand.EXECUTION_ERROR;
    }
}
