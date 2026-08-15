package com.minos.cli;

import com.minos.context.CodeSearchCriteria;
import com.minos.context.CodeSearchResponse;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolSearchCriteria;
import com.minos.output.CodeSearchRenderer;
import com.minos.output.SymbolOutputFormat;

import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Recherche structurée et contextuelle M4.
 */
public final class SearchCodeCommand {

    public static final String NAME = "search";

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 20;
    private static final Set<String> SUPPORTED_OPTIONS = Set.of(
            "--qualified-name", "--kind", "--module", "--limit", "--depth",
            "--usages", "--relationships", "--context-lines", "--max-tokens",
            "--no-source", "--format"
    );
    private static final String USAGE = """
            Usage: minos search <project> <query> [options]

            Options:
              --qualified-name <name>  Filter by exact qualified name
              --kind <kind>            Filter by symbol kind
              --module <module>        Filter by module identifier
              --limit <count>          Root symbols (default: 5, max: 20)
              --depth <0..3>           Relationship traversal depth (default: 1)
              --usages <0..50>         Usages per root symbol (default: 3)
              --relationships <0..50>  Relationships per traversed node (default: 10)
              --context-lines <0..50>  Source lines around declarations (default: 2)
              --max-tokens <count>     Estimated context budget (default: 4000)
              --no-source              Omit relevant source ranges
              --format <text|json>     Output format (default: text)
              -h, --help               Show this help
            """.stripTrailing();

    private final ProjectSymbolQuery query;

    public SearchCodeCommand(ProjectSymbolQuery query) {
        this.query = Objects.requireNonNull(query, "query");
    }

    public int run(String[] arguments, Appendable output, Appendable error) throws IOException {
        return CliCommandSupport.run(arguments, output, error, USAGE, Options::parse, "search", options -> {
            CodeSearchResponse response = query.searchCode(
                    options.projectId(),
                    options.criteria()
            );
            output.append(CodeSearchRenderer.render(response, options.format())).append('\n');
            return FindSymbolCommand.SUCCESS;
        });
    }

    public static String usage() {
        return USAGE;
    }

    private record Options(
            String projectId,
            CodeSearchCriteria criteria,
            SymbolOutputFormat format
    ) {
        private static Options parse(String[] arguments) {
            if (arguments.length < 2) {
                throw new IllegalArgumentException("expected <project> and <query>");
            }
            String project = operand(arguments[0], "project");
            String text = operand(arguments[1], "query");
            String qualifiedName = null;
            String module = null;
            SymbolKind kind = null;
            int limit = DEFAULT_LIMIT;
            int depth = 1;
            int usages = 3;
            int relationships = 10;
            int contextLines = 2;
            int maxTokens = 4_000;
            boolean includeSource = true;
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
                if ("--no-source".equals(option)) {
                    includeSource = false;
                    continue;
                }
                if (++index >= arguments.length || arguments[index] == null
                        || arguments[index].isBlank() || arguments[index].startsWith("--")) {
                    throw new IllegalArgumentException("missing value for " + option);
                }
                String value = arguments[index];
                switch (option) {
                    case "--qualified-name" -> qualifiedName = value;
                    case "--kind" -> kind = parseKind(value);
                    case "--module" -> module = value;
                    case "--limit" -> limit = bounded(value, option, 1, MAX_LIMIT);
                    case "--depth" -> depth = bounded(
                            value, option, 0, CodeSearchCriteria.MAX_DEPTH);
                    case "--usages" -> usages = bounded(
                            value, option, 0, CodeSearchCriteria.MAX_ITEMS_PER_NODE);
                    case "--relationships" -> relationships = bounded(
                            value, option, 0, CodeSearchCriteria.MAX_ITEMS_PER_NODE);
                    case "--context-lines" -> contextLines = bounded(
                            value, option, 0, CodeSearchCriteria.MAX_CONTEXT_LINES);
                    case "--max-tokens" -> maxTokens = bounded(
                            value, option, CodeSearchCriteria.MIN_TOKEN_BUDGET,
                            CodeSearchCriteria.MAX_TOKEN_BUDGET);
                    case "--format" -> format = SymbolOutputFormat.parse(value);
                    default -> throw new IllegalStateException("unhandled option: " + option);
                }
            }
            return new Options(
                    project,
                    new CodeSearchCriteria(
                            new SymbolSearchCriteria(text, qualifiedName, kind, module, limit),
                            depth, usages, relationships, contextLines, maxTokens, includeSource
                    ),
                    format
            );
        }

        private static String operand(String value, String name) {
            if (value == null || value.isBlank() || value.startsWith("-")) {
                throw new IllegalArgumentException("invalid <" + name + "> operand");
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

        private static int bounded(String raw, String option, int minimum, int maximum) {
            try {
                int value = Integer.parseInt(raw);
                if (value < minimum || value > maximum) {
                    throw new IllegalArgumentException(
                            option + " must be between " + minimum + " and " + maximum);
                }
                return value;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("invalid value for " + option + ": " + raw,
                        exception);
            }
        }
    }
}
