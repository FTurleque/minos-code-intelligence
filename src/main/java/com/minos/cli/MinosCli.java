package com.minos.cli;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Dispatcher minimal des commandes CLI MINOS.
 *
 * <p>Le bootstrap produit construit cette classe avec les ports reliés au
 * registre et au snapshot actif. Aucun fournisseur n'est sélectionné ici.</p>
 */
public final class MinosCli {

    private static final String USAGE = """
            Usage: minos <command> [arguments]

            Commands:
              find-symbol  Find normalized symbols in an active project index
              search  Build a bounded code context from symbols, sources and relations
              get-source  Explicitly retrieve a complete local source file
              find-usages  Find resolved usages of a normalized symbol
              find-implementations  Find implementation-navigation relations
              find-callers  Find incoming CALLS relations when available
              find-callees  Find outgoing CALLS relations when available
              dependencies  Find outgoing DEPENDS_ON relations
              dependents  Find incoming DEPENDS_ON relations
              related-tests  Find explained tests related to a production symbol

            Run `minos <command> --help` for command options.
            """.stripTrailing();

    private final FindSymbolCommand findSymbolCommand;
    private final SearchCodeCommand searchCodeCommand;
    private final GetSourceCommand getSourceCommand;
    private final FindUsagesCommand findUsagesCommand;
    private final java.util.Map<String, RelationshipCommand> relationshipCommands;

    public MinosCli(ProjectSymbolQuery symbolQuery) {
        this.findSymbolCommand = new FindSymbolCommand(
                Objects.requireNonNull(symbolQuery, "symbolQuery")
        );
        this.searchCodeCommand = new SearchCodeCommand(symbolQuery);
        this.getSourceCommand = new GetSourceCommand(symbolQuery);
        this.findUsagesCommand = new FindUsagesCommand(symbolQuery);
        java.util.Map<String, RelationshipCommand> commands = new java.util.LinkedHashMap<>();
        for (RelationshipCommand.Operation operation : RelationshipCommand.Operation.values()) {
            commands.put(
                    operation.commandName(),
                    new RelationshipCommand(operation, symbolQuery)
            );
        }
        this.relationshipCommands = java.util.Map.copyOf(commands);
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

        if (FindSymbolCommand.NAME.equals(arguments[0])) {
            return findSymbolCommand.run(
                    Arrays.copyOfRange(arguments, 1, arguments.length),
                    output,
                    error
            );
        }
        if (SearchCodeCommand.NAME.equals(arguments[0])) {
            return searchCodeCommand.run(
                    Arrays.copyOfRange(arguments, 1, arguments.length),
                    output,
                    error
            );
        }
        if (GetSourceCommand.NAME.equals(arguments[0])) {
            return getSourceCommand.run(
                    Arrays.copyOfRange(arguments, 1, arguments.length),
                    output,
                    error
            );
        }
        if (FindUsagesCommand.NAME.equals(arguments[0])) {
            return findUsagesCommand.run(
                    Arrays.copyOfRange(arguments, 1, arguments.length),
                    output,
                    error
            );
        }
        RelationshipCommand relationshipCommand = relationshipCommands.get(arguments[0]);
        if (relationshipCommand != null) {
            return relationshipCommand.run(
                    Arrays.copyOfRange(arguments, 1, arguments.length),
                    output,
                    error
            );
        }

        error.append("error: unknown command: ").append(arguments[0]).append('\n');
        error.append(USAGE).append('\n');
        return FindSymbolCommand.USAGE_ERROR;
    }

    public static String usage() {
        return USAGE;
    }
}
