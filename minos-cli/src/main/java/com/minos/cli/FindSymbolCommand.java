package com.minos.cli;

import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolSearchCriteria;
import com.minos.output.SymbolOutputFormat;
import com.minos.output.SymbolResultRenderer;
import com.minos.query.SymbolResult;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Commande CLI minimale de recherche de symboles.
 */
public final class FindSymbolCommand {

    public static final String NAME = "find-symbol";
    public static final int SUCCESS = 0;
    public static final int EXECUTION_ERROR = 1;
    public static final int USAGE_ERROR = 2;

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 1_000;

    private static final Set<String> SUPPORTED_OPTIONS = Set.of(
            "--qualified-name",
            "--kind",
            "--module",
            "--limit",
            "--format"
    );
    private static final String USAGE = """
            Usage: minos find-symbol <project> <symbol> [options]

            Options:
              --qualified-name <name>  Filter by exact qualified name
              --kind <kind>            Filter by symbol kind
              --module <module>        Filter by module identifier
              --limit <count>          Maximum results (default: 20, max: 1000)
              --format <text|json>     Output format (default: text)
              -h, --help               Show this help
            """.stripTrailing();

    private final ProjectSymbolQuery symbolQuery;

    public FindSymbolCommand(ProjectSymbolQuery symbolQuery) {
        this.symbolQuery = Objects.requireNonNull(symbolQuery, "symbolQuery");
    }

    public int run(String[] arguments, Appendable output, Appendable error) throws IOException {
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(error, "error");

        if (arguments.length == 1
                && ("--help".equals(arguments[0]) || "-h".equals(arguments[0]))) {
            output.append(USAGE).append('\n');
            return SUCCESS;
        }

        Options options;
        try {
            options = Options.parse(arguments);
        } catch (IllegalArgumentException exception) {
            error.append("error: ").append(exception.getMessage()).append('\n');
            error.append(USAGE).append('\n');
            return USAGE_ERROR;
        }

        List<SymbolResult> results;
        try {
            results = List.copyOf(symbolQuery.findSymbols(
                    options.projectId(),
                    options.criteria()
            ));
        } catch (Exception exception) {
            error.append("error: find-symbol failed: ")
                    .append(failureMessage(exception))
                    .append('\n');
            return EXECUTION_ERROR;
        }

        output.append(SymbolResultRenderer.render(results, options.format())).append('\n');
        return SUCCESS;
    }

    public static String usage() {
        return USAGE;
    }

    private static String failureMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message.replace('\r', ' ').replace('\n', ' ');
    }

    private record Options(
            String projectId,
            SymbolSearchCriteria criteria,
            SymbolOutputFormat format
    ) {

        private static Options parse(String[] arguments) {
            if (arguments.length < 2) {
                throw new IllegalArgumentException("expected <project> and <symbol>");
            }

            String projectId = requireOperand(arguments[0], "project");
            String symbol = requireOperand(arguments[1], "symbol");
            String qualifiedName = null;
            SymbolKind kind = null;
            String moduleId = null;
            int limit = DEFAULT_LIMIT;
            SymbolOutputFormat format = SymbolOutputFormat.TEXT;
            Set<String> seenOptions = new HashSet<>();

            for (int index = 2; index < arguments.length; index++) {
                String option = arguments[index];
                if (option == null) {
                    throw new IllegalArgumentException(
                            "argument at index " + index + " must not be null"
                    );
                }
                if (!option.startsWith("--")) {
                    throw new IllegalArgumentException("unexpected argument: " + option);
                }
                if (!SUPPORTED_OPTIONS.contains(option)) {
                    throw new IllegalArgumentException("unknown option: " + option);
                }
                if (!seenOptions.add(option)) {
                    throw new IllegalArgumentException("duplicate option: " + option);
                }

                String value = optionValue(arguments, ++index, option);
                switch (option) {
                    case "--qualified-name" -> qualifiedName = requireValue(value, option);
                    case "--kind" -> kind = parseKind(requireValue(value, option));
                    case "--module" -> moduleId = requireValue(value, option);
                    case "--limit" -> limit = CliCommandSupport.parseLimit(requireValue(value, option), MAX_LIMIT);
                    case "--format" -> format = SymbolOutputFormat.parse(
                            requireValue(value, option)
                    );
                    default -> throw new IllegalStateException("unhandled option: " + option);
                }
            }

            return new Options(
                    projectId,
                    new SymbolSearchCriteria(symbol, qualifiedName, kind, moduleId, limit),
                    format
            );
        }

        private static String requireOperand(String value, String name) {
            if (value == null || value.isBlank() || value.startsWith("-")) {
                throw new IllegalArgumentException("invalid <" + name + "> operand");
            }
            return value;
        }

        private static String optionValue(String[] arguments, int index, String option) {
            if (index >= arguments.length) {
                throw new IllegalArgumentException("missing value for " + option);
            }
            return arguments[index];
        }

        private static String requireValue(String value, String option) {
            if (value == null || value.isBlank() || value.startsWith("--")) {
                throw new IllegalArgumentException("missing value for " + option);
            }
            return value;
        }

        private static SymbolKind parseKind(String value) {
            try {
                return SymbolKind.valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("unsupported symbol kind: " + value, exception);
            }
        }

    }
}
