package com.minos.cli;

import com.minos.output.CodeIntelligenceResultRenderer;
import com.minos.output.SymbolOutputFormat;
import com.minos.query.UsageResult;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Commande CLI M3 de recherche des usages résolus d'un symbole.
 */
public final class FindUsagesCommand {

    public static final String NAME = "find-usages";
    private static final Set<String> SUPPORTED_OPTIONS = Set.of("--limit", "--format");
    private static final String USAGE = """
            Usage: minos find-usages <project> <symbol-id> [options]

            Options:
              --limit <count>          Maximum results (default: 20, max: 1000)
              --format <text|json>     Output format (default: text)
              -h, --help               Show this help
            """.stripTrailing();

    private final ProjectSymbolQuery query;

    public FindUsagesCommand(ProjectSymbolQuery query) {
        this.query = Objects.requireNonNull(query, "query");
    }

    public int run(String[] arguments, Appendable output, Appendable error) throws IOException {
        if (arguments.length == 1 && isHelp(arguments[0])) {
            output.append(USAGE).append('\n');
            return FindSymbolCommand.SUCCESS;
        }
        Options options;
        try {
            options = Options.parse(arguments);
        } catch (IllegalArgumentException exception) {
            error.append("error: ").append(exception.getMessage()).append('\n')
                    .append(USAGE).append('\n');
            return FindSymbolCommand.USAGE_ERROR;
        }

        try {
            List<UsageResult> usages = List.copyOf(query.findUsages(
                    options.projectId(),
                    options.symbolId(),
                    options.limit()
            ));
            output.append(CodeIntelligenceResultRenderer.renderUsages(usages, options.format()))
                    .append('\n');
            return FindSymbolCommand.SUCCESS;
        } catch (Exception exception) {
            error.append("error: find-usages failed: ")
                    .append(failureMessage(exception)).append('\n');
            return FindSymbolCommand.EXECUTION_ERROR;
        }
    }

    static String usage() {
        return USAGE;
    }

    private static boolean isHelp(String value) {
        return "--help".equals(value) || "-h".equals(value);
    }

    private static String failureMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message.replace('\r', ' ').replace('\n', ' ');
    }

    private record Options(
            String projectId,
            String symbolId,
            int limit,
            SymbolOutputFormat format
    ) {
        private static Options parse(String[] arguments) {
            if (arguments.length < 2) {
                throw new IllegalArgumentException("expected <project> and <symbol-id>");
            }
            String projectId = operand(arguments[0], "project");
            String symbolId = operand(arguments[1], "symbol-id");
            int limit = FindSymbolCommand.DEFAULT_LIMIT;
            SymbolOutputFormat format = SymbolOutputFormat.TEXT;
            Set<String> seen = new HashSet<>();
            for (int index = 2; index < arguments.length; index++) {
                String option = arguments[index];
                if (option == null || !SUPPORTED_OPTIONS.contains(option)) {
                    throw new IllegalArgumentException("unknown option: " + option);
                }
                if (!seen.add(option)) {
                    throw new IllegalArgumentException("duplicate option: " + option);
                }
                if (++index >= arguments.length) {
                    throw new IllegalArgumentException("missing value for " + option);
                }
                String value = arguments[index];
                if (value == null || value.isBlank() || value.startsWith("--")) {
                    throw new IllegalArgumentException("missing value for " + option);
                }
                if ("--limit".equals(option)) {
                    limit = parseLimit(value);
                } else {
                    format = SymbolOutputFormat.parse(value);
                }
            }
            return new Options(projectId, symbolId, limit, format);
        }

        private static String operand(String value, String name) {
            if (value == null || value.isBlank() || value.startsWith("-")) {
                throw new IllegalArgumentException("invalid <" + name + "> operand");
            }
            return value;
        }

        private static int parseLimit(String value) {
            try {
                int limit = Integer.parseInt(value);
                if (limit < 1 || limit > FindSymbolCommand.MAX_LIMIT) {
                    throw new IllegalArgumentException(
                            "limit must be between 1 and " + FindSymbolCommand.MAX_LIMIT
                    );
                }
                return limit;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("invalid limit: " + value, exception);
            }
        }
    }
}
