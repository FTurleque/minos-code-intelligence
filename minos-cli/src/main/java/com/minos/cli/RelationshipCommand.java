package com.minos.cli;

import com.minos.application.ProjectSymbolQuery;
import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.RelationshipKind;
import com.minos.domain.RelationshipSearchCriteria;
import com.minos.output.CodeIntelligenceResultRenderer;
import com.minos.output.SymbolOutputFormat;
import com.minos.query.RelationshipResult;

import java.io.IOException;
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

    private static final CliOptions.Spec OPTIONS = CliOptions.spec()
            .text("--format")
            .integer("--limit", 1, FindSymbolCommand.MAX_LIMIT);

    private final Operation operation;
    private final ProjectSymbolQuery query;

    public RelationshipCommand(Operation operation, ProjectSymbolQuery query) {
        this.operation = Objects.requireNonNull(operation, "operation");
        this.query = Objects.requireNonNull(query, "query");
    }

    public int run(String[] arguments, Appendable output, Appendable error) throws IOException {
        return CliCommandSupport.run(arguments, output, error, usage(operation), Options::parse, operation.commandName,
                options -> {
                    CodeEntityRef anchor = new CodeEntityRef(CodeEntityType.SYMBOL, options.symbolId());
                    RelationshipSearchCriteria criteria = operation.incoming
                            ? RelationshipSearchCriteria.incoming(anchor, Set.of(operation.kind), options.limit())
                            : RelationshipSearchCriteria.outgoing(anchor, Set.of(operation.kind), options.limit());
                    List<RelationshipResult> relationships =
                            List.copyOf(query.findRelationships(options.projectId(), criteria));
                    List<String> limitations =
                            query.relationshipLimitations(options.projectId(), Set.of(operation.kind));
                    output.append(CodeIntelligenceResultRenderer.renderRelationships(
                                    relationships, limitations, options.format()))
                            .append('\n');
                    return FindSymbolCommand.SUCCESS;
                });
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

    private record Options(String projectId, String symbolId, int limit, SymbolOutputFormat format) {
        private static Options parse(String[] arguments) {
            if (arguments.length < 2) {
                throw new IllegalArgumentException("expected <project> and <symbol-id>");
            }
            String project = CliCommandSupport.operand(arguments[0], "project");
            String symbol = CliCommandSupport.operand(arguments[1], "symbol-id");
            CliOptions options = OPTIONS.parse(arguments, 2);
            return new Options(project, symbol, options.integer("--limit", FindSymbolCommand.DEFAULT_LIMIT),
                    options.format());
        }
    }
}
