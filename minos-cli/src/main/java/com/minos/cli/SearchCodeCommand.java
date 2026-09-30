package com.minos.cli;

import com.minos.application.ProjectSymbolQuery;
import com.minos.context.CodeSearchCriteria;
import com.minos.context.CodeSearchResponse;
import com.minos.domain.SymbolSearchCriteria;
import com.minos.output.CodeSearchRenderer;
import com.minos.output.SymbolOutputFormat;

import java.io.IOException;
import java.util.Objects;

/**
 * Recherche structurée et contextuelle M4.
 */
public final class SearchCodeCommand {

    public static final String NAME = "search";

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 20;
    private static final CliOptions.Spec OPTIONS = CliOptions.spec()
            .text("--qualified-name", "--kind", "--module", "--format")
            .flag("--no-source")
            .integer("--limit", 1, MAX_LIMIT)
            .integer("--depth", 0, CodeSearchCriteria.MAX_DEPTH)
            .integer("--usages", 0, CodeSearchCriteria.MAX_ITEMS_PER_NODE)
            .integer("--relationships", 0, CodeSearchCriteria.MAX_ITEMS_PER_NODE)
            .integer("--context-lines", 0, CodeSearchCriteria.MAX_CONTEXT_LINES)
            .integer("--max-tokens", CodeSearchCriteria.MIN_TOKEN_BUDGET, CodeSearchCriteria.MAX_TOKEN_BUDGET);
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
            String project = CliCommandSupport.operand(arguments[0], "project");
            String text = CliCommandSupport.operand(arguments[1], "query");
            CliOptions options = OPTIONS.parse(arguments, 2);
            return new Options(
                    project,
                    new CodeSearchCriteria(
                            new SymbolSearchCriteria(text, options.text("--qualified-name"),
                                    CliCommandSupport.symbolKind(options.text("--kind")), options.text("--module"),
                                    options.integer("--limit", DEFAULT_LIMIT)),
                            options.integer("--depth", 1),
                            options.integer("--usages", 3),
                            options.integer("--relationships", 10),
                            options.integer("--context-lines", 2),
                            options.integer("--max-tokens", 4_000),
                            !options.has("--no-source")
                    ),
                    options.format()
            );
        }
    }
}
