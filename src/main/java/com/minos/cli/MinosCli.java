package com.minos.cli;

import com.minos.architecture.ProjectArchitectureQuery;
import com.minos.impact.ProjectImpactQuery;
import com.minos.integration.nexus.NexusExportService;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Dispatcher stable des commandes CLI MINOS.
 *
 * <p>La CLI reste une couche d'exposition : les opérations métier sont portées
 * par les services injectés et non réimplémentées dans le dispatcher.</p>
 */
public final class MinosCli {

    private static final String USAGE = """
            Usage: minos <command> [arguments]

            Project and index:
              project add       Register a local project
              project list      List registered projects
              project inspect   Inspect discovery and active index state
              inspect           Alias for project inspect
              index             Import an explicit SCIP artifact into a MINOS snapshot
              index-status      Show active snapshot and known CLI index metadata

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
              nexus-export       Export the active normalized snapshot as NEXUS contract JSON

            Exit codes:
              0  success
              1  execution failure
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
    private final ArchitectureCommand architectureCommand;
    private final ImpactCommand impactCommand;
    private final NexusExportCommand nexusExportCommand;

    /**
     * Constructeur historique utilisé par les tests et adaptateurs ne nécessitant
     * que les requêtes symbole/relation.
     */
    public MinosCli(ProjectSymbolQuery symbolQuery) {
        this(symbolQuery, null, null, null, null);
    }

    public MinosCli(
            ProjectSymbolQuery symbolQuery,
            ProjectOperations projectOperations,
            ProjectArchitectureQuery architectureQuery,
            ProjectImpactQuery impactQuery
    ) {
        this(symbolQuery, projectOperations, architectureQuery, impactQuery, null);
    }

    public MinosCli(
            ProjectSymbolQuery symbolQuery,
            ProjectOperations projectOperations,
            ProjectArchitectureQuery architectureQuery,
            ProjectImpactQuery impactQuery,
            NexusExportService nexusExportService
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
        this.indexCommand = projectOperations == null ? null : new IndexCommand(projectOperations);
        this.architectureCommand = architectureQuery == null ? null : new ArchitectureCommand(architectureQuery);
        this.impactCommand = impactQuery == null ? null : new ImpactCommand(impactQuery);
        this.nexusExportCommand = nexusExportService == null ? null : new NexusExportCommand(nexusExportService);
    }

    public int run(String[] arguments, Appendable output, Appendable error) throws IOException {
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(error, "error");

        if (arguments.length == 1
                && ("--help".equals(arguments[0]) || "-h".equals(arguments[0]))) {
            output.append(USAGE).append('\n');
            return FindSymbolCommand.SUCCESS;
        }
        if (arguments.length == 0) {
            error.append("error: command is required\n").append(USAGE).append('\n');
            return FindSymbolCommand.USAGE_ERROR;
        }

        String command = arguments[0];
        String[] commandArguments = Arrays.copyOfRange(arguments, 1, arguments.length);
        if (ProjectCommand.NAME.equals(command)) {
            return projectCommand == null
                    ? unavailable(command, error)
                    : projectCommand.run(commandArguments, output, error);
        }
        if ("inspect".equals(command)) {
            return projectCommand == null
                    ? unavailable(command, error)
                    : projectCommand.runInspectAlias(commandArguments, output, error);
        }
        if (IndexCommand.NAME.equals(command)) {
            return indexCommand == null
                    ? unavailable(command, error)
                    : indexCommand.run(commandArguments, output, error);
        }
        if ("index-status".equals(command)) {
            return projectCommand == null
                    ? unavailable(command, error)
                    : projectCommand.runIndexStatus(commandArguments, output, error);
        }
        if (ArchitectureCommand.NAME.equals(command)) {
            return architectureCommand == null
                    ? unavailable(command, error)
                    : architectureCommand.run(commandArguments, output, error);
        }
        if (ImpactCommand.NAME.equals(command)) {
            return impactCommand == null
                    ? unavailable(command, error)
                    : impactCommand.run(commandArguments, output, error);
        }
        if (NexusExportCommand.NAME.equals(command)) {
            return nexusExportCommand == null
                    ? unavailable(command, error)
                    : nexusExportCommand.run(commandArguments, output, error);
        }
        if (FindSymbolCommand.NAME.equals(command)) {
            return findSymbolCommand.run(commandArguments, output, error);
        }
        if (SearchCodeCommand.NAME.equals(command)) {
            return searchCodeCommand.run(commandArguments, output, error);
        }
        if (GetSourceCommand.NAME.equals(command)) {
            return getSourceCommand.run(commandArguments, output, error);
        }
        if (FindUsagesCommand.NAME.equals(command)) {
            return findUsagesCommand.run(commandArguments, output, error);
        }
        RelationshipCommand relationshipCommand = relationshipCommands.get(command);
        if (relationshipCommand != null) {
            return relationshipCommand.run(commandArguments, output, error);
        }

        error.append("error: unknown command: ").append(command).append('\n');
        error.append(USAGE).append('\n');
        return FindSymbolCommand.USAGE_ERROR;
    }

    public static String usage() {
        return USAGE;
    }

    private static int unavailable(String command, Appendable error) throws IOException {
        error.append("error: ").append(command)
                .append(" is not configured in this CLI bootstrap\n");
        return FindSymbolCommand.EXECUTION_ERROR;
    }
}
