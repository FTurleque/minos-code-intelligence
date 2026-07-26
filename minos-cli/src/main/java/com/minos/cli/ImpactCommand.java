package com.minos.cli;

import com.minos.domain.Symbol;
import com.minos.impact.ImpactAnalysisReport;
import com.minos.impact.ImpactAnalysisRequest;
import com.minos.impact.ImpactPathStep;
import com.minos.impact.ImpactedSymbol;
import com.minos.impact.ProjectImpactQuery;
import com.minos.output.SymbolOutputFormat;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Exposition CLI de l'analyse d'impact M8.
 */
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
            output.append(render(report, options.format())).append('\n');
            return FindSymbolCommand.SUCCESS;
        } catch (Exception exception) {
            error.append("error: impact failed: ").append(failureMessage(exception)).append('\n');
            return FindSymbolCommand.EXECUTION_ERROR;
        }
    }

    public static String usage() {
        return USAGE;
    }

    private static String render(ImpactAnalysisReport report, SymbolOutputFormat format) {
        if (format == SymbolOutputFormat.JSON) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("projectId", report.projectId().toString());
            map.put("snapshotId", report.snapshotId());
            map.put("nature", report.nature().name());
            map.put("rootSymbol", symbolMap(report.rootSymbol()));
            map.put("maxDepth", report.request().maxDepth());
            map.put("maxResults", report.request().maxResults());
            map.put("impactCount", report.impacts().size());
            map.put("testCount", report.potentiallyImpactedTests().size());
            map.put("limitations", report.limitations().stream().map(Enum::name).toList());
            map.put("impacts", report.impacts().stream().map(ImpactCommand::impactMap).toList());
            map.put("potentiallyImpactedTests", report.potentiallyImpactedTests().stream()
                    .map(ImpactCommand::impactMap).toList());
            return CliJson.render(map);
        }
        StringBuilder builder = new StringBuilder();
        builder.append("root: ").append(display(report.rootSymbol())).append('\n');
        builder.append("snapshot: ").append(report.snapshotId()).append('\n');
        builder.append("impacts: ").append(report.impacts().size()).append('\n');
        builder.append("potentiallyImpactedTests: ").append(report.potentiallyImpactedTests().size()).append('\n');
        builder.append("limitations: ").append(report.limitations()).append('\n');
        for (ImpactedSymbol impact : report.impacts()) {
            builder.append("- ")
                    .append(impact.level()).append(" depth=").append(impact.depth())
                    .append(" confidence=").append(impact.confidence())
                    .append(" test=").append(impact.testImpact())
                    .append(" ").append(display(impact.symbol()))
                    .append(" path=").append(impact.path().stream()
                            .map(step -> step.relationshipKind() + ":" + step.relationshipId())
                            .toList())
                    .append('\n');
        }
        if (!builder.isEmpty()) {
            builder.setLength(builder.length() - 1);
        }
        return builder.toString();
    }

    private static Map<String, Object> impactMap(ImpactedSymbol impact) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("symbol", symbolMap(impact.symbol()));
        map.put("level", impact.level().name());
        map.put("depth", impact.depth());
        map.put("confidence", impact.confidence());
        map.put("nature", impact.nature().name());
        map.put("testImpact", impact.testImpact());
        map.put("path", impact.path().stream().map(ImpactCommand::stepMap).toList());
        return map;
    }

    private static Map<String, Object> stepMap(ImpactPathStep step) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("changedSymbolId", step.changedSymbolId());
        map.put("impactedSymbolId", step.impactedSymbolId());
        map.put("relationshipId", step.relationshipId());
        map.put("relationshipKind", step.relationshipKind().name());
        map.put("relationshipNature", step.relationshipNature().name());
        map.put("confidence", step.confidence());
        return map;
    }

    private static Map<String, Object> symbolMap(Symbol symbol) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", symbol.id());
        map.put("name", symbol.name());
        map.put("qualifiedName", symbol.qualifiedName());
        map.put("kind", symbol.kind().name());
        map.put("signature", symbol.signature());
        map.put("fileId", symbol.fileId());
        map.put("moduleId", symbol.moduleId());
        return map;
    }

    private static String display(Symbol symbol) {
        return (symbol.qualifiedName() == null ? symbol.name() : symbol.qualifiedName())
                + " [" + symbol.id() + "]";
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
