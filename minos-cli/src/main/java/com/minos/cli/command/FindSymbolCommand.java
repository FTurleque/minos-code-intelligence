package com.minos.cli.command;

import com.minos.cli.support.CliCommandSupport;
import com.minos.cli.support.CliExitCodes;
import com.minos.cli.support.CliOptions;
import com.minos.application.ProjectSymbolQuery;
import com.minos.domain.SymbolSearchCriteria;
import com.minos.output.SymbolOutputFormat;
import com.minos.output.SymbolResultRenderer;
import com.minos.query.SymbolResult;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Commande CLI minimale de recherche de symboles. */
public final class FindSymbolCommand {

    public static final String NAME = "find-symbol";

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 1_000;

    private static final CliOptions.Spec OPTIONS = CliOptions.spec()
            .text("--qualified-name", "--kind", "--module", "--format")
            .integer("--limit", 1, MAX_LIMIT);
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
        return CliCommandSupport.run(arguments, output, error, USAGE, Options::parse, NAME, options -> {
            List<SymbolResult> results = List.copyOf(symbolQuery.findSymbols(options.projectId(), options.criteria()));
            output.append(SymbolResultRenderer.render(results, options.format())).append('\n');
            return CliExitCodes.SUCCESS;
        });
    }

    public static String usage() {
        return USAGE;
    }

    private record Options(String projectId, SymbolSearchCriteria criteria, SymbolOutputFormat format) {

        private static Options parse(String[] arguments) {
            if (arguments.length < 2) {
                throw new IllegalArgumentException("expected <project> and <symbol>");
            }
            String projectId = CliCommandSupport.operand(arguments[0], "project");
            String symbol = CliCommandSupport.operand(arguments[1], "symbol");
            CliOptions options = OPTIONS.parse(arguments, 2);
            return new Options(projectId,
                    new SymbolSearchCriteria(symbol, options.text("--qualified-name"),
                            CliCommandSupport.symbolKind(options.text("--kind")), options.text("--module"),
                            options.integer("--limit", DEFAULT_LIMIT)),
                    options.format());
        }
    }
}
