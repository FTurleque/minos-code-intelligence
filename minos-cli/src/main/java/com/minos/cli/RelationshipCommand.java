package com.minos.cli;

import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.RelationshipKind;
import com.minos.domain.RelationshipSearchCriteria;
import com.minos.output.CodeIntelligenceResultRenderer;
import com.minos.output.SymbolOutputFormat;
import com.minos.query.RelationshipResult;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Commande paramétrée pour les vues relationnelles M3/M5. */
public final class RelationshipCommand {

    public enum Operation {
        IMPLEMENTATIONS("find-implementations", true, RelationshipKind.IMPLEMENTS),
        CALLERS("find-callers", true, RelationshipKind.CALLS),
        CALLEES("find-callees", false, RelationshipKind.CALLS),
        DEPENDENCIES("dependencies", false, RelationshipKind.DEPENDS_ON),
        DEPENDENTS("dependents", true, RelationshipKind.DEPENDS_ON),
        RELATED_TESTS("related-tests", true, RelationshipKind.RELATED_TEST);

        private final String commandName;
        private final boolean incoming;
        private final RelationshipKind kind;

        Operation(String commandName, boolean incoming, RelationshipKind kind) {
            this.commandName = commandName;
            this.incoming = incoming;
            this.kind = kind;
        }

        public String commandName() {
            return commandName;
        }
    }

    private static final Set<String> SUPPORTED_OPTIONS = Set.of("--limit", "--format");

    private final Operation operation;
    private final ProjectSymbolQuery query;

    public RelationshipCommand(Operation operation, ProjectSymbolQuery query) {
        this.operation = Objects.requireNonNull(operation, "operation");
        this.query = Objects.requireNonNull(query, "query");
    }

    public int run(String[] arguments, Appendable output, Appendable error) throws IOException {
        String usage = usage(operation);
        if (arguments.length == 1 && isHelp(arguments[0])) {
            output.append(usage).append('\n');
            return FindSymbolCommand.SUCCESS;
        }
        Options options;
        try {
            options = Options.parse(arguments);
        } catch (IllegalArgumentException exception) {
            error.append("error: ").append(exception.getMessage()).append('\n')
                    .append(usage).append('\n');
            return FindSymbolCommand.USAGE_ERROR;
        }

        CodeEntityRef anchor = new CodeEntityRef(CodeEntityType.SYMBOL, options.symbolId());
        RelationshipSearchCriteria criteria = operation.incoming
                ? RelationshipSearchCriteria.incoming(anchor, Set.of(operation.kind), options.limit())
                : RelationshipSearchCriteria.outgoing(anchor, Set.of(operation.kind), options.limit());
        try {
            List<RelationshipResult> relationships = List.copyOf(query.findRelationships(options.projectId(), criteria));
            output.append(CodeIntelligenceResultRenderer.renderRelationships(relationships, options.format())).append('\n');
            return FindSymbolCommand.SUCCESS;
        } catch (Exception exception) {
            error.append("error: ").append(operation.commandName).append(" failed: ")
                    .append(CliCommandSupport.failureMessage(exception)).append('\n');
            return FindSymbolCommand.EXECUTION_ERROR;
        }
    }

    public static String usage(Operation operation) {
        return ("""
                Usage: minos %s <project> <symbol-id> [options]

                Options:
                  --limit <count>          Maximum results (default: 20, max: 1000)
                  --format <text|json>     Output format (default: text)
                  -h, --help               Show this help
                """).formatted(operation.commandName).stripTrailing();
    }

    private static boolean isHelp(String value) {
        return "--help".equals(value) || "-h".equals(value);
    }

    private record Options(String projectId, String symbolId, int limit, SymbolOutputFormat format) {
        private static Options parse(String[] arguments) {
            if (arguments.length < 2) {
                throw new IllegalArgumentException("expected <project> and <symbol-id>");
            }
            String project = CliCommandSupport.operand(arguments[0], "project");
            String symbol = CliCommandSupport.operand(arguments[1], "symbol-id");
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
            return new Options(project, symbol, limit, format);
        }
    }
}
