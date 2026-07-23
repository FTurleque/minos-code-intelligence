package com.minos.cli;

import com.minos.output.SymbolOutputFormat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Commande d'indexation locale M9.
 *
 * <p>Le dépôt ne contient pas encore de runner de processus d'indexeur de
 * production. La CLI n'invente donc pas cette capacité : elle importe un
 * artefact SCIP explicitement fourni puis publie le snapshot MINOS normalisé.</p>
 */
public final class IndexCommand {

    public static final String NAME = "index";
    private static final Set<String> SUPPORTED_OPTIONS = Set.of(
            "--scip", "--provider", "--provider-version", "--module", "--snapshot", "--format"
    );
    private static final String USAGE = """
            Usage: minos index <project> --scip <index.scip> --provider <id> [options]

            Options:
              --provider-version <version>  Provider version when known
              --module <module>             Module identifier associated with the import
              --snapshot <id>               Explicit snapshot id; default derives from artifact SHA-256
              --format <text|json>          Output format (default: text)
              -h, --help                    Show this help

            This command imports an existing SCIP artifact. It does not claim to launch
            scip-java or scip-typescript automatically.
            """.stripTrailing();

    private final ProjectOperations operations;

    public IndexCommand(ProjectOperations operations) {
        this.operations = Objects.requireNonNull(operations, "operations");
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
            ProjectOperations.IndexImportResult result = operations.importScip(
                    options.project(),
                    options.scipFile(),
                    options.providerId(),
                    options.providerVersion(),
                    options.moduleId(),
                    options.snapshotId()
            );
            output.append(render(result, options.format())).append('\n');
            return FindSymbolCommand.SUCCESS;
        } catch (Exception exception) {
            error.append("error: index failed: ").append(failureMessage(exception)).append('\n');
            return FindSymbolCommand.EXECUTION_ERROR;
        }
    }

    public static String usage() {
        return USAGE;
    }

    private static String render(ProjectOperations.IndexImportResult result, SymbolOutputFormat format) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("projectId", result.projectId());
        map.put("snapshotId", result.snapshotId());
        map.put("providerId", result.providerId());
        map.put("providerVersion", result.providerVersion());
        map.put("normalizedSymbolCount", result.normalizedSymbolCount());
        map.put("occurrenceCount", result.occurrenceCount());
        map.put("relationshipCount", result.relationshipCount());
        map.put("relatedTestRelationshipCount", result.relatedTestRelationshipCount());
        map.put("unresolvedOccurrenceCount", result.unresolvedOccurrenceCount());
        map.put("unresolvedRelationshipCount", result.unresolvedRelationshipCount());
        map.put("completedAt", result.completedAt());
        if (format == SymbolOutputFormat.JSON) {
            return CliJson.render(map);
        }
        return String.join("\n",
                "projectId: " + result.projectId(),
                "snapshotId: " + result.snapshotId(),
                "providerId: " + result.providerId(),
                "providerVersion: " + nullable(result.providerVersion()),
                "normalizedSymbols: " + result.normalizedSymbolCount(),
                "occurrences: " + result.occurrenceCount(),
                "relationships: " + result.relationshipCount(),
                "relatedTests: " + result.relatedTestRelationshipCount(),
                "unresolvedOccurrences: " + result.unresolvedOccurrenceCount(),
                "unresolvedRelationships: " + result.unresolvedRelationshipCount(),
                "completedAt: " + result.completedAt()
        );
    }

    private static String nullable(String value) {
        return value == null ? "-" : value;
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
            Path scipFile,
            String providerId,
            String providerVersion,
            String moduleId,
            String snapshotId,
            SymbolOutputFormat format
    ) {
        private static Options parse(String[] arguments) {
            if (arguments.length < 1) {
                throw new IllegalArgumentException("expected <project>");
            }
            String project = operand(arguments[0], "project");
            Path scip = null;
            String provider = null;
            String providerVersion = null;
            String module = null;
            String snapshot = null;
            SymbolOutputFormat format = SymbolOutputFormat.TEXT;
            Set<String> seen = new HashSet<>();
            for (int index = 1; index < arguments.length; index++) {
                String option = arguments[index];
                if (option == null || !SUPPORTED_OPTIONS.contains(option)) {
                    throw new IllegalArgumentException("unknown option: " + option);
                }
                if (!seen.add(option)) {
                    throw new IllegalArgumentException("duplicate option: " + option);
                }
                if (++index >= arguments.length || arguments[index] == null || arguments[index].isBlank()
                        || arguments[index].startsWith("--")) {
                    throw new IllegalArgumentException("missing value for " + option);
                }
                String value = arguments[index];
                switch (option) {
                    case "--scip" -> scip = Path.of(value);
                    case "--provider" -> provider = value;
                    case "--provider-version" -> providerVersion = value;
                    case "--module" -> module = value;
                    case "--snapshot" -> snapshot = value;
                    case "--format" -> format = SymbolOutputFormat.parse(value);
                    default -> throw new IllegalStateException("unhandled option: " + option);
                }
            }
            if (scip == null) {
                throw new IllegalArgumentException("--scip is required");
            }
            if (provider == null || provider.isBlank()) {
                throw new IllegalArgumentException("--provider is required");
            }
            return new Options(project, scip, provider, providerVersion, module, snapshot, format);
        }

        private static String operand(String value, String name) {
            if (value == null || value.isBlank() || value.startsWith("-")) {
                throw new IllegalArgumentException("invalid <" + name + "> operand");
            }
            return value;
        }
    }
}
