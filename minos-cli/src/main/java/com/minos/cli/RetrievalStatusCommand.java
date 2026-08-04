package com.minos.cli;

import com.minos.output.SymbolOutputFormat;
import com.minos.semantic.SemanticIndexService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Read-only status surface for semantic and hybrid retrieval readiness. */
final class RetrievalStatusCommand {

    enum Mode {
        SEMANTIC("semantic"),
        HYBRID("hybrid");

        private final String commandName;

        Mode(String commandName) {
            this.commandName = commandName;
        }

        String commandName() {
            return commandName;
        }
    }

    @FunctionalInterface
    interface StatusReader {
        SemanticIndexService.Status status(String projectReference) throws IOException;
    }

    private final Mode mode;
    private final StatusReader statusReader;

    RetrievalStatusCommand(Mode mode, SemanticIndexService service) {
        this(mode, Objects.requireNonNull(service, "service")::status);
    }

    RetrievalStatusCommand(Mode mode, StatusReader statusReader) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.statusReader = Objects.requireNonNull(statusReader, "statusReader");
    }

    int run(String[] arguments, Appendable output, Appendable error) throws IOException {
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(error, "error");
        if (arguments.length == 1 && isHelp(arguments[0])) {
            output.append(usage(mode)).append('\n');
            return FindSymbolCommand.SUCCESS;
        }
        if (arguments.length == 2 && "status".equals(arguments[0]) && isHelp(arguments[1])) {
            output.append(usage(mode)).append('\n');
            return FindSymbolCommand.SUCCESS;
        }
        try {
            Options options = Options.parse(arguments);
            SemanticIndexService.Status status = statusReader.status(options.projectReference());
            output.append(render(status, mode, options.format())).append('\n');
            return FindSymbolCommand.SUCCESS;
        } catch (IllegalArgumentException exception) {
            error.append("error: ").append(exception.getMessage()).append('\n')
                    .append(usage(mode)).append('\n');
            return FindSymbolCommand.USAGE_ERROR;
        } catch (RuntimeException exception) {
            error.append("error: ").append(mode.commandName()).append(" status failed: ")
                    .append(message(exception)).append('\n');
            return FindSymbolCommand.EXECUTION_ERROR;
        }
    }

    static String usage(Mode mode) {
        return "Usage: minos " + mode.commandName() + " status <project> [--format <text|json>]";
    }

    private static String render(SemanticIndexService.Status status, Mode mode, SymbolOutputFormat format) {
        Map<String, Object> value = map(status, mode);
        if (format == SymbolOutputFormat.JSON) return CliJson.render(value);
        List<String> lines = new ArrayList<>();
        value.forEach((key, item) -> lines.add(key + ": " + item));
        return String.join("\n", lines);
    }

    private static Map<String, Object> map(SemanticIndexService.Status status, Mode mode) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("mode", mode.commandName());
        value.put("projectId", status.projectId());
        value.put("projectName", status.projectName());
        value.put("state", mode == Mode.SEMANTIC ? status.state().name() : hybridState(status));
        value.put("activeSnapshotId", status.activeSnapshotId());
        value.put("semanticState", status.state().name());
        value.put("semanticAvailable", status.state() == SemanticIndexService.State.READY);
        value.put("providerId", status.providerId());
        value.put("modelId", status.modelId());
        value.put("dimensions", status.dimensions());
        value.put("documentCount", status.documentCount());
        value.put("indexSizeBytes", status.indexSizeBytes());
        value.put("limitations", limitations(status, mode));
        return value;
    }

    private static String hybridState(SemanticIndexService.Status status) {
        if (status.activeSnapshotId() == null) return "NO_ACTIVE_SNAPSHOT";
        return status.state() == SemanticIndexService.State.READY
                ? "READY_WITH_SEMANTIC"
                : "READY_STRUCTURED_FALLBACK";
    }

    private static List<String> limitations(SemanticIndexService.Status status, Mode mode) {
        List<String> limitations = new ArrayList<>(status.limitations());
        if (mode == Mode.HYBRID) {
            limitations.add("HYBRID_RANKING_IS_DERIVED_SELECTION_NOT_CODE_FACT");
            if (status.state() == SemanticIndexService.State.READY) {
                limitations.add("SEMANTIC_SIGNAL_IS_HEURISTIC_NOT_STRUCTURAL_FACT");
            } else if (status.activeSnapshotId() != null) {
                limitations.add("SEMANTIC_SIGNAL_UNAVAILABLE_STRUCTURED_FALLBACK_USED");
            }
        }
        return List.copyOf(limitations);
    }

    private static boolean isHelp(String value) {
        return "--help".equals(value) || "-h".equals(value);
    }

    private static String message(RuntimeException exception) {
        String value = exception.getMessage();
        return value == null || value.isBlank() ? exception.getClass().getSimpleName() : value;
    }

    private record Options(String projectReference, SymbolOutputFormat format) {
        static Options parse(String[] arguments) {
            if (arguments.length < 2 || !"status".equals(arguments[0])) {
                throw new IllegalArgumentException("status and a project reference are required");
            }
            String project = null;
            SymbolOutputFormat format = SymbolOutputFormat.TEXT;
            int i = 1;
            while (i < arguments.length) {
                String argument = arguments[i];
                if ("--format".equals(argument)) {
                    i++;
                    if (i >= arguments.length) throw new IllegalArgumentException("--format requires a value");
                    format = switch (arguments[i].toLowerCase()) {
                        case "text" -> SymbolOutputFormat.TEXT;
                        case "json" -> SymbolOutputFormat.JSON;
                        default -> throw new IllegalArgumentException("unsupported format: " + arguments[i]);
                    };
                } else if (argument.startsWith("--")) {
                    throw new IllegalArgumentException("unknown option: " + argument);
                } else if (project == null) {
                    project = argument;
                } else {
                    throw new IllegalArgumentException("unexpected argument: " + argument);
                }
                i++;
            }
            if (project == null || project.isBlank()) throw new IllegalArgumentException("project reference is required");
            return new Options(project, format);
        }
    }
}
