package com.minos.cli;

import com.minos.impact.ImpactAnalysisReport;
import com.minos.impact.ImpactAnalysisRequest;
import com.minos.impact.ProjectImpactQuery;
import com.minos.output.ImpactResultRenderer;
import com.minos.output.SymbolOutputFormat;

import java.io.IOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

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

    private final ProjectImpactQuery query;

    public ImpactCommand(ProjectImpactQuery query) {
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
            ImpactAnalysisReport report = query.analyzeImpact(
                    options.project(),
                    new ImpactAnalysisRequest(options.symbolId(), options.depth(), options.limit())
            );
            output.append(ImpactResultRenderer.render(report, options.format())).append('\n');
            return FindSymbolCommand.SUCCESS;
        } catch (Exception exception) {
            error.append("error: impact failed: ").append(failureMessage(exception)).append('\n');
            return FindSymbolCommand.EXECUTION_ERROR;
        }
    }

    public static String usage() {
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
            String project = operand(arguments[0], "project");
            String symbol = operand(arguments[1], "symbol-id");
            int depth = 4;
            int limit = 200;
            SymbolOutputFormat format = SymbolOutputFormat.TEXT;
            Set<String> seen = new HashSet<>();
            for (int index = 2; index < arguments.length; index++) {
                String option = arguments[index];
                if (!Set.of("--depth", "--limit", "--format").contains(option)) {
                    throw new IllegalArgumentException("unknown option: " + option);
                }
                if (!seen.add(option)) {
                    throw new IllegalArgumentException("duplicate option: " + option);
                }
                if (++index >= arguments.length || arguments[index] == null || arguments[index].isBlank()) {
                    throw new IllegalArgumentException("missing value for " + option);
                }
                String value = arguments[index];
                switch (option) {
                    case "--depth" -> depth = integer(value, option, 1, 32);
                    case "--limit" -> limit = integer(value, option, 1, 10_000);
                    case "--format" -> format = SymbolOutputFormat.parse(value);
                    default -> throw new IllegalStateException("unhandled option: " + option);
                }
            }
            return new Options(project, symbol, depth, limit, format);
        }

        private static int integer(String value, String option, int minimum, int maximum) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed < minimum || parsed > maximum) {
                    throw new IllegalArgumentException(option + " must be between " + minimum + " and " + maximum);
                }
                return parsed;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("invalid value for " + option + ": " + value, exception);
            }
        }

        private static String operand(String value, String name) {
            if (value == null || value.isBlank() || value.startsWith("-")) {
                throw new IllegalArgumentException("invalid <" + name + "> operand");
            }
            return value;
        }
    }
}
