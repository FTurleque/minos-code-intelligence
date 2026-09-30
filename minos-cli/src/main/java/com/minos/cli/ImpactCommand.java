package com.minos.cli;

import com.minos.impact.ImpactAnalysisReport;
import com.minos.impact.ImpactAnalysisRequest;
import com.minos.impact.ProjectImpactQuery;
import com.minos.output.ImpactResultRenderer;
import com.minos.output.SymbolOutputFormat;

import java.io.IOException;
import java.util.Objects;

/** CLI adapter for M8 impact analysis. */
public final class ImpactCommand {

    public static final String NAME = "impact";
    private static final String USAGE = """
            Usage: minos impact <project> <symbol-id> [options]

            Options:
              --depth <1..32>           Maximum propagation depth (default: 4)
              --limit <1..10000>        Maximum impacted symbols (default: 200)
              --format <text|json>      Output format (default: text)
              -h, --help                Show this help
            """.stripTrailing();

    private static final CliOptions.Spec OPTIONS = CliOptions.spec()
            .text("--format")
            .integer("--depth", 1, 32)
            .integer("--limit", 1, 10_000);

    private final ProjectImpactQuery query;

    public ImpactCommand(ProjectImpactQuery query) {
        this.query = Objects.requireNonNull(query, "query");
    }

    public int run(String[] arguments, Appendable output, Appendable error) throws IOException {
        return CliCommandSupport.run(arguments, output, error, USAGE, Options::parse, NAME, options -> {
            ImpactAnalysisReport report = query.analyzeImpact(
                    options.project(),
                    new ImpactAnalysisRequest(options.symbolId(), options.depth(), options.limit())
            );
            output.append(ImpactResultRenderer.render(report, options.format())).append('\n');
            return FindSymbolCommand.SUCCESS;
        });
    }

    public static String usage() {
        return USAGE;
    }

    private record Options(
            String project,
            String symbolId,
            int depth,
            int limit,
            SymbolOutputFormat format
    ) {
        private static Options parse(String[] arguments) {
            if (arguments.length < 2) {
                throw new IllegalArgumentException("expected <project> and <symbol-id>");
            }
            String project = CliCommandSupport.operand(arguments[0], "project");
            String symbol = CliCommandSupport.operand(arguments[1], "symbol-id");
            CliOptions options = OPTIONS.parse(arguments, 2);
            return new Options(project, symbol, options.integer("--depth", 4), options.integer("--limit", 200),
                    options.format());
        }
    }
}
