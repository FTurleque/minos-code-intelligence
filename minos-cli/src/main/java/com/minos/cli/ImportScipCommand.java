package com.minos.cli;

import com.minos.output.SymbolOutputFormat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Import manuel explicite d'un artefact SCIP. */
public final class ImportScipCommand {

    public static final String NAME = "import-scip";
    private static final Set<String> OPTIONS = Set.of(
            "--file", "--provider", "--provider-version", "--module", "--snapshot", "--format");
    private static final String USAGE = """
            Usage: minos import-scip <project> --file <index.scip> --provider <id> [options]

              --provider-version <version>
              --module <module>
              --snapshot <id>
              --format <text|json>
            """.stripTrailing();

    private final ProjectOperations operations;

    public ImportScipCommand(ProjectOperations operations) {
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    public int run(String[] arguments, Appendable output, Appendable error) throws IOException {
        return CliCommandSupport.run(arguments, output, error, USAGE, Options::parse, NAME, options -> {
            ProjectOperations.IndexImportResult result = operations.importScip(
                    options.project, options.file, options.provider, options.providerVersion,
                    options.module, options.snapshot);
            String diagnostic = CliCommandSupport.publicDiagnostic(result.diagnostic());
            output.append(render(result, options.format, diagnostic)).append('\n');
            String warning = warning(result.commitStatus());
            if (warning != null) {
                error.append("warning: ").append(warning)
                        .append(diagnostic == null ? "" : ": " + diagnostic)
                        .append('\n');
            }
            return FindSymbolCommand.SUCCESS;
        });
    }

    private static String warning(ProjectOperations.IndexImportCommitStatus status) {
        return switch (status) {
            case COMMITTED -> null;
            case COMMITTED_DURABILITY_PENDING ->
                    "snapshot committed and authoritative but durability acknowledgement is pending";
            case COMMITTED_METADATA_PENDING ->
                    "snapshot committed but project metadata recovery is pending";
            case COMMITTED_DURABILITY_AND_METADATA_PENDING ->
                    "snapshot committed and authoritative but durability acknowledgement and metadata recovery are pending";
        };
    }

    private static String render(
            ProjectOperations.IndexImportResult result,
            SymbolOutputFormat format,
            String diagnostic
    ) {
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
        map.put("commitStatus", result.commitStatus().name());
        map.put("diagnostic", diagnostic);
        if (format == SymbolOutputFormat.JSON) {
            return CliJson.render(map);
        }
        return "projectId: " + result.projectId() + "\n"
                + "snapshotId: " + result.snapshotId() + "\n"
                + "providerId: " + result.providerId() + "\n"
                + "normalizedSymbols: " + result.normalizedSymbolCount() + "\n"
                + "occurrences: " + result.occurrenceCount() + "\n"
                + "relationships: " + result.relationshipCount() + "\n"
                + "commitStatus: " + result.commitStatus().name()
                + (diagnostic == null ? "" : "\ndiagnostic: " + diagnostic);
    }

    private static final class Options {
        private final String project;
        private final Path file;
        private final String provider;
        private final String providerVersion;
        private final String module;
        private final String snapshot;
        private final SymbolOutputFormat format;

        private Options(String project, Path file, String provider, String providerVersion,
                        String module, String snapshot, SymbolOutputFormat format) {
            this.project = project;
            this.file = file;
            this.provider = provider;
            this.providerVersion = providerVersion;
            this.module = module;
            this.snapshot = snapshot;
            this.format = format;
        }

        private static Options parse(String[] arguments) {
            if (arguments.length < 1) {
                throw new IllegalArgumentException("expected <project>");
            }
            String project = CliCommandSupport.operand(arguments[0], "project");
            Path file = null;
            String provider = null;
            String providerVersion = null;
            String module = null;
            String snapshot = null;
            SymbolOutputFormat format = SymbolOutputFormat.TEXT;
            Set<String> seen = new HashSet<>();
            for (int i = 1; i < arguments.length; i++) {
                String option = arguments[i];
                if (!OPTIONS.contains(option) || !seen.add(option)) {
                    throw new IllegalArgumentException("unknown or duplicate option: " + option);
                }
                if (++i >= arguments.length || arguments[i] == null || arguments[i].isBlank()) {
                    throw new IllegalArgumentException("missing value for " + option);
                }
                String value = arguments[i];
                switch (option) {
                    case "--file" -> file = Path.of(value);
                    case "--provider" -> provider = value;
                    case "--provider-version" -> providerVersion = value;
                    case "--module" -> module = value;
                    case "--snapshot" -> snapshot = value;
                    case "--format" -> format = SymbolOutputFormat.parse(value);
                    default -> throw new IllegalStateException("unhandled option: " + option);
                }
            }
            if (file == null) {
                throw new IllegalArgumentException("--file is required");
            }
            if (provider == null || provider.isBlank()) {
                throw new IllegalArgumentException("--provider is required");
            }
            return new Options(project, file, provider, providerVersion, module, snapshot, format);
        }
    }
}
