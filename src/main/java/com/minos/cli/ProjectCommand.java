package com.minos.cli;

import com.minos.output.SymbolOutputFormat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Commandes stables d'administration du registre projet.
 */
public final class ProjectCommand {

    public static final String NAME = "project";
    private static final String USAGE = """
            Usage:
              minos project add <path> [--name <name>] [--format <text|json>]
              minos project list [--format <text|json>]
              minos project inspect <project> [--format <text|json>]

            Aliases:
              minos inspect <project> [--format <text|json>]
              minos index-status <project> [--format <text|json>]
            """.stripTrailing();

    private final ProjectOperations operations;

    public ProjectCommand(ProjectOperations operations) {
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    public int run(String[] arguments, Appendable output, Appendable error) throws IOException {
        if (arguments.length == 1 && isHelp(arguments[0])) {
            output.append(USAGE).append('\n');
            return FindSymbolCommand.SUCCESS;
        }
        if (arguments.length == 0) {
            return usageError("project subcommand is required", output, error);
        }
        return switch (arguments[0]) {
            case "add" -> runAdd(slice(arguments, 1), output, error);
            case "list" -> runList(slice(arguments, 1), output, error);
            case "inspect" -> runInspectAlias(slice(arguments, 1), output, error);
            default -> usageError("unknown project subcommand: " + arguments[0], output, error);
        };
    }

    public int runInspectAlias(String[] arguments, Appendable output, Appendable error) throws IOException {
        Options options;
        try {
            options = Options.singleProject(arguments);
        } catch (IllegalArgumentException exception) {
            return commandUsageError(exception, "Usage: minos inspect <project> [--format <text|json>]", error);
        }
        try {
            ProjectOperations.ProjectView project = operations.inspectProject(options.project());
            output.append(renderProject(project, options.format())).append('\n');
            return FindSymbolCommand.SUCCESS;
        } catch (Exception exception) {
            return executionError("inspect", exception, error);
        }
    }

    public int runIndexStatus(String[] arguments, Appendable output, Appendable error) throws IOException {
        Options options;
        try {
            options = Options.singleProject(arguments);
        } catch (IllegalArgumentException exception) {
            return commandUsageError(exception, "Usage: minos index-status <project> [--format <text|json>]", error);
        }
        try {
            ProjectOperations.ProjectView project = operations.inspectProject(options.project());
            output.append(renderIndexStatus(project, options.format())).append('\n');
            return FindSymbolCommand.SUCCESS;
        } catch (Exception exception) {
            return executionError("index-status", exception, error);
        }
    }

    public static String usage() {
        return USAGE;
    }

    private int runAdd(String[] arguments, Appendable output, Appendable error) throws IOException {
        AddOptions options;
        try {
            options = AddOptions.parse(arguments);
        } catch (IllegalArgumentException exception) {
            return commandUsageError(
                    exception,
                    "Usage: minos project add <path> [--name <name>] [--format <text|json>]",
                    error
            );
        }
        try {
            ProjectOperations.ProjectView project = operations.addProject(options.path(), options.name());
            output.append(renderProject(project, options.format())).append('\n');
            return FindSymbolCommand.SUCCESS;
        } catch (Exception exception) {
            return executionError("project add", exception, error);
        }
    }

    private int runList(String[] arguments, Appendable output, Appendable error) throws IOException {
        SymbolOutputFormat format;
        try {
            format = parseFormatOnly(arguments);
        } catch (IllegalArgumentException exception) {
            return commandUsageError(
                    exception,
                    "Usage: minos project list [--format <text|json>]",
                    error
            );
        }
        try {
            List<ProjectOperations.ProjectView> projects = operations.listProjects();
            output.append(renderProjects(projects, format)).append('\n');
            return FindSymbolCommand.SUCCESS;
        } catch (Exception exception) {
            return executionError("project list", exception, error);
        }
    }

    private static String renderProjects(List<ProjectOperations.ProjectView> projects, SymbolOutputFormat format) {
        if (format == SymbolOutputFormat.JSON) {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("count", projects.size());
            root.put("projects", projects.stream().map(ProjectCommand::projectMap).toList());
            return CliJson.render(root);
        }
        if (projects.isEmpty()) {
            return "No projects registered.";
        }
        List<String> lines = new ArrayList<>();
        for (ProjectOperations.ProjectView project : projects) {
            lines.add(project.id() + "\t" + project.name() + "\t" + project.indexState() + "\t" + project.rootPath());
        }
        return String.join("\n", lines);
    }

    private static String renderProject(ProjectOperations.ProjectView project, SymbolOutputFormat format) {
        if (format == SymbolOutputFormat.JSON) {
            return CliJson.render(projectMap(project));
        }
        return String.join("\n",
                "id: " + project.id(),
                "name: " + project.name(),
                "root: " + project.rootPath(),
                "rootAvailable: " + project.rootAvailable(),
                "languages: " + project.languages(),
                "buildSystems: " + project.buildSystems(),
                "modules: " + project.moduleCount(),
                "indexState: " + project.indexState(),
                "activeSnapshot: " + nullable(project.activeSnapshotId()),
                "lastSuccessfulIndexAt: " + nullable(project.lastSuccessfulIndexAt()),
                "provider: " + nullable(project.providerId()),
                "providerVersion: " + nullable(project.providerVersion())
        );
    }

    private static String renderIndexStatus(ProjectOperations.ProjectView project, SymbolOutputFormat format) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("projectId", project.id());
        status.put("projectName", project.name());
        status.put("state", project.indexState());
        status.put("activeSnapshotId", project.activeSnapshotId());
        status.put("lastSuccessfulIndexAt", project.lastSuccessfulIndexAt());
        status.put("providerId", project.providerId());
        status.put("providerVersion", project.providerVersion());
        if (format == SymbolOutputFormat.JSON) {
            return CliJson.render(status);
        }
        return String.join("\n",
                "projectId: " + project.id(),
                "projectName: " + project.name(),
                "state: " + project.indexState(),
                "activeSnapshotId: " + nullable(project.activeSnapshotId()),
                "lastSuccessfulIndexAt: " + nullable(project.lastSuccessfulIndexAt()),
                "providerId: " + nullable(project.providerId()),
                "providerVersion: " + nullable(project.providerVersion())
        );
    }

    private static Map<String, Object> projectMap(ProjectOperations.ProjectView project) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", project.id());
        map.put("name", project.name());
        map.put("rootPath", project.rootPath());
        map.put("rootAvailable", project.rootAvailable());
        map.put("languages", project.languages());
        map.put("buildSystems", project.buildSystems());
        map.put("moduleCount", project.moduleCount());
        map.put("indexState", project.indexState());
        map.put("activeSnapshotId", project.activeSnapshotId());
        map.put("lastSuccessfulIndexAt", project.lastSuccessfulIndexAt());
        map.put("providerId", project.providerId());
        map.put("providerVersion", project.providerVersion());
        return map;
    }

    private static SymbolOutputFormat parseFormatOnly(String[] arguments) {
        if (arguments.length == 0) {
            return SymbolOutputFormat.TEXT;
        }
        if (arguments.length == 1 && isHelp(arguments[0])) {
            throw new IllegalArgumentException("help");
        }
        if (arguments.length != 2 || !"--format".equals(arguments[0])) {
            throw new IllegalArgumentException("only --format is supported");
        }
        return SymbolOutputFormat.parse(arguments[1]);
    }

    private int usageError(String message, Appendable output, Appendable error) throws IOException {
        error.append("error: ").append(message).append('\n').append(USAGE).append('\n');
        return FindSymbolCommand.USAGE_ERROR;
    }

    private static int commandUsageError(IllegalArgumentException exception, String usage, Appendable error)
            throws IOException {
        if (!"help".equals(exception.getMessage())) {
            error.append("error: ").append(exception.getMessage()).append('\n');
        }
        error.append(usage).append('\n');
        return FindSymbolCommand.USAGE_ERROR;
    }

    private static int executionError(String command, Exception exception, Appendable error) throws IOException {
        error.append("error: ").append(command).append(" failed: ")
                .append(failureMessage(exception)).append('\n');
        return FindSymbolCommand.EXECUTION_ERROR;
    }

    private static String failureMessage(Exception exception) {
        Throwable effective = exception;
        if (exception instanceof RuntimeException && exception.getCause() != null) {
            effective = exception.getCause();
        }
        String message = effective.getMessage();
        return message == null || message.isBlank()
                ? effective.getClass().getSimpleName()
                : message.replace('\r', ' ').replace('\n', ' ');
    }

    private static String nullable(String value) {
        return value == null ? "-" : value;
    }

    private static String[] slice(String[] values, int from) {
        return java.util.Arrays.copyOfRange(values, from, values.length);
    }

    private static boolean isHelp(String value) {
        return "--help".equals(value) || "-h".equals(value);
    }

    private record Options(String project, SymbolOutputFormat format) {
        private static Options singleProject(String[] arguments) {
            if (arguments.length == 1 && isHelp(arguments[0])) {
                throw new IllegalArgumentException("help");
            }
            if (arguments.length < 1) {
                throw new IllegalArgumentException("expected <project>");
            }
            String project = operand(arguments[0], "project");
            SymbolOutputFormat format = SymbolOutputFormat.TEXT;
            if (arguments.length > 1) {
                if (arguments.length != 3 || !"--format".equals(arguments[1])) {
                    throw new IllegalArgumentException("unexpected arguments");
                }
                format = SymbolOutputFormat.parse(arguments[2]);
            }
            return new Options(project, format);
        }
    }

    private record AddOptions(Path path, String name, SymbolOutputFormat format) {
        private static AddOptions parse(String[] arguments) {
            if (arguments.length == 1 && isHelp(arguments[0])) {
                throw new IllegalArgumentException("help");
            }
            if (arguments.length < 1) {
                throw new IllegalArgumentException("expected <path>");
            }
            String rawPath = operand(arguments[0], "path");
            Path path = Path.of(rawPath);
            String name = defaultName(path);
            SymbolOutputFormat format = SymbolOutputFormat.TEXT;
            Set<String> seen = new HashSet<>();
            for (int index = 1; index < arguments.length; index++) {
                String option = arguments[index];
                if (!Set.of("--name", "--format").contains(option)) {
                    throw new IllegalArgumentException("unknown option: " + option);
                }
                if (!seen.add(option)) {
                    throw new IllegalArgumentException("duplicate option: " + option);
                }
                if (++index >= arguments.length || arguments[index] == null || arguments[index].isBlank()) {
                    throw new IllegalArgumentException("missing value for " + option);
                }
                if ("--name".equals(option)) {
                    name = arguments[index];
                } else {
                    format = SymbolOutputFormat.parse(arguments[index]);
                }
            }
            return new AddOptions(path, name, format);
        }

        private static String defaultName(Path path) {
            Path fileName = path.toAbsolutePath().normalize().getFileName();
            return fileName == null ? "project" : fileName.toString();
        }
    }

    private static String operand(String value, String name) {
        if (value == null || value.isBlank() || value.startsWith("-")) {
            throw new IllegalArgumentException("invalid <" + name + "> operand");
        }
        return value;
    }
}
