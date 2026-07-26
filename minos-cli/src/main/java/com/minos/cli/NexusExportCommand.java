package com.minos.cli;

import com.minos.integration.nexus.NexusExportContract;
import com.minos.integration.nexus.NexusExportService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only CLI transport used by the optional NEXUS integration.
 */
public final class NexusExportCommand {

    public static final String NAME = "nexus-export";

    private static final String USAGE = """
            Usage: minos nexus-export --root <project-root>

            Exports the active normalized MINOS knowledge snapshot as the versioned
            M13 JSON contract consumed by NEXUS. The command is read-only and never
            ranks or selects final LLM context.

            Options:
              --root <path>  Registered project root to export
              -h, --help     Show this help
            """.stripTrailing();

    private final ExportOperation exportOperation;

    public NexusExportCommand(NexusExportService exportService) {
        this(Objects.requireNonNull(exportService, "exportService")::export);
    }

    NexusExportCommand(ExportOperation exportOperation) {
        this.exportOperation = Objects.requireNonNull(exportOperation, "exportOperation");
    }

    public int run(String[] arguments, Appendable output, Appendable error) throws IOException {
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(error, "error");

        if (arguments.length == 1
                && ("--help".equals(arguments[0]) || "-h".equals(arguments[0]))) {
            output.append(USAGE).append('\n');
            return FindSymbolCommand.SUCCESS;
        }

        Path root;
        try {
            root = parseRoot(arguments);
        } catch (IllegalArgumentException exception) {
            error.append("error: ").append(exception.getMessage()).append('\n');
            error.append(USAGE).append('\n');
            return FindSymbolCommand.USAGE_ERROR;
        }

        NexusExportContract.ExportSnapshot snapshot;
        try {
            snapshot = exportOperation.export(root);
        } catch (Exception exception) {
            error.append("error: nexus-export failed: ")
                    .append(failureMessage(exception))
                    .append('\n');
            return FindSymbolCommand.EXECUTION_ERROR;
        }

        output.append(CliJson.render(snapshotJson(snapshot))).append('\n');
        return FindSymbolCommand.SUCCESS;
    }

    public static String usage() {
        return USAGE;
    }

    private static Path parseRoot(String[] arguments) {
        if (arguments.length != 2 || !"--root".equals(arguments[0])) {
            throw new IllegalArgumentException("expected --root <project-root>");
        }
        String rawRoot = arguments[1];
        if (rawRoot == null || rawRoot.isBlank() || rawRoot.startsWith("--")) {
            throw new IllegalArgumentException("missing value for --root");
        }
        return Path.of(rawRoot);
    }

    private static Map<String, Object> snapshotJson(NexusExportContract.ExportSnapshot snapshot) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("contractVersion", snapshot.contractVersion());
        json.put("producer", snapshot.producer());
        json.put("project", projectJson(snapshot.project()));
        json.put("symbols", snapshot.symbols().stream().map(NexusExportCommand::symbolJson).toList());
        json.put("relations", snapshot.relations().stream().map(NexusExportCommand::relationJson).toList());
        json.put("limitations", snapshot.limitations());
        return json;
    }

    private static Map<String, Object> projectJson(NexusExportContract.ExportProject project) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", project.id());
        json.put("name", project.name());
        json.put("rootPath", project.rootPath());
        json.put("snapshotId", project.snapshotId());
        return json;
    }

    private static Map<String, Object> symbolJson(NexusExportContract.ExportSymbol symbol) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", symbol.id());
        json.put("symbolKey", symbol.symbolKey());
        json.put("filePath", symbol.filePath());
        json.put("moduleId", symbol.moduleId());
        json.put("kind", symbol.kind());
        json.put("name", symbol.name());
        json.put("qualifiedName", symbol.qualifiedName());
        json.put("signature", symbol.signature());
        json.put("language", symbol.language());
        json.put("startLine", symbol.startLine());
        json.put("endLine", symbol.endLine());
        json.put("resolutionStatus", symbol.resolutionStatus());
        json.put("identityQuality", symbol.identityQuality());
        json.put("generated", symbol.generated());
        json.put("origin", originJson(symbol.origin()));
        return json;
    }

    private static Map<String, Object> relationJson(NexusExportContract.ExportRelation relation) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", relation.id());
        json.put("filePath", relation.filePath());
        json.put("kind", relation.kind());
        json.put("sourceId", relation.sourceId());
        json.put("sourceQualifiedName", relation.sourceQualifiedName());
        json.put("targetId", relation.targetId());
        json.put("targetQualifiedName", relation.targetQualifiedName());
        json.put("resolutionStatus", relation.resolutionStatus());
        json.put("nature", relation.nature());
        json.put("confidence", relation.confidence());
        json.put("origin", originJson(relation.origin()));
        json.put("evidence", relation.evidence().stream().map(NexusExportCommand::evidenceJson).toList());
        return json;
    }

    private static Map<String, Object> originJson(NexusExportContract.ExportOrigin origin) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("providerId", origin.providerId());
        json.put("providerType", origin.providerType());
        json.put("providerVersion", origin.providerVersion());
        json.put("indexRunId", origin.indexRunId());
        json.put("sourceType", origin.sourceType());
        return json;
    }

    private static Map<String, Object> evidenceJson(NexusExportContract.ExportEvidence evidence) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("type", evidence.type());
        json.put("description", evidence.description());
        json.put("weight", evidence.weight());
        return json;
    }

    private static String failureMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message.replace('\r', ' ').replace('\n', ' ');
    }

    @FunctionalInterface
    interface ExportOperation {
        NexusExportContract.ExportSnapshot export(Path projectRoot) throws Exception;
    }
}
