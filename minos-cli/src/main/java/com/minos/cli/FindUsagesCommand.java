package com.minos.cli;

import com.minos.application.ProjectSymbolQuery;
import com.minos.output.CodeIntelligenceResultRenderer;
import com.minos.output.SymbolOutputFormat;
import com.minos.query.UsageResult;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Commande CLI M3 de recherche des usages résolus d'un symbole.
 */
public final class FindUsagesCommand {

    public static final String NAME = "find-usages";
    private static final CliOptions.Spec OPTIONS = CliOptions.spec()
            .text("--format")
            .integer("--limit", 1, FindSymbolCommand.MAX_LIMIT);
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
        return CliCommandSupport.run(arguments, output, error, USAGE, Options::parse, NAME, options -> {
            List<UsageResult> usages = List.copyOf(query.findUsages(
                    options.projectId(),
                    options.symbolId(),
                    options.limit()
            ));
            output.append(CodeIntelligenceResultRenderer.renderUsages(usages, options.format()))
                    .append('\n');
            return FindSymbolCommand.SUCCESS;
        });
    }

    static String usage() {
        return USAGE;
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
            String projectId = CliCommandSupport.operand(arguments[0], "project");
            String symbolId = CliCommandSupport.operand(arguments[1], "symbol-id");
            CliOptions options = OPTIONS.parse(arguments, 2);
            return new Options(projectId, symbolId, options.integer("--limit", FindSymbolCommand.DEFAULT_LIMIT),
                    options.format());
        }
    }
}
