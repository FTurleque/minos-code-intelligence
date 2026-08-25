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
                    limit = CliCommandSupport.parseLimit(value, FindSymbolCommand.MAX_LIMIT);
                } else {
                    format = SymbolOutputFormat.parse(value);
                }
            }
            return new Options(projectId, symbolId, limit, format);
        }


    }
}
