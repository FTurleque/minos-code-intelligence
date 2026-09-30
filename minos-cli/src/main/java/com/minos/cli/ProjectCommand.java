package com.minos.cli;

import com.minos.application.ProjectOperations;
import com.minos.orchestration.ResumableRunSummary;
import com.minos.output.ProjectJson;
import com.minos.output.SymbolOutputFormat;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Commandes stables d'administration du registre projet. */
public final class ProjectCommand {

    public static final String NAME = "project";
    private static final String ADD_USAGE =
            "Usage: minos project add <path> [--name <name>] [--format <text|json>]";
    private static final String LIST_USAGE =
            "Usage: minos project list [--format <text|json>]";
    private static final String INSPECT_USAGE =
            "Usage: minos inspect <project> [--format <text|json>]";
    private static final String STATUS_USAGE =
            "Usage: minos index-status <project> [--format <text|json>]";
    private static final String USAGE = """
            Usage:
              minos project add <path> [--name <name>] [--format <text|json>]
              minos project list [--format <text|json>]
              minos project inspect <project> [--format <text|json>]

            Aliases:
              minos inspect <project> [--format <text|json>]
              minos index-status <project> [--format <text|json>]
            """.stripTrailing();

    private static final CliOptions.Spec FORMAT_ONLY = CliOptions.spec().text("--format");
    private static final CliOptions.Spec ADD_OPTIONS = CliOptions.spec().text("--name", "--format");

    private final ProjectOperations operations;
    private final IndexResumeStatusSource resumeStatus;

    public ProjectCommand(ProjectOperations operations) {
        this(operations, projectId -> Optional.empty());
    }

    /** R1 (ADR 0039 §6) : `index-status` expose le run reprenable fourni par {@code resumeStatus}. */
    public ProjectCommand(ProjectOperations operations, IndexResumeStatusSource resumeStatus) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.resumeStatus = Objects.requireNonNull(resumeStatus, "resumeStatus");
    }

    public int run(String[] arguments, Appendable output, Appendable error) throws IOException {
        if (arguments.length == 1 && CliCommandSupport.isHelp(arguments[0])) {
            output.append(USAGE).append('\n');
            return FindSymbolCommand.SUCCESS;
        }
        if (arguments.length == 0) {
            return usageError("project subcommand is required", error);
        }
        return switch (arguments[0]) {
            case "add" -> runAdd(slice(arguments, 1), output, error);
            case "list" -> runList(slice(arguments, 1), output, error);
            case "inspect" -> runInspectAlias(slice(arguments, 1), output, error);
            default -> usageError("unknown project subcommand: " + arguments[0], error);
        };
    }

    public int runInspectAlias(String[] arguments, Appendable output, Appendable error) throws IOException {
        return CliCommandSupport.run(arguments, output, error, INSPECT_USAGE, Options::singleProject,
                CliCommandSupport.reportingCause("inspect"), options -> {
                    ProjectOperations.ProjectView project = operations.inspectProject(options.project());
                    output.append(renderProject(project, options.format())).append('\n');
                    return FindSymbolCommand.SUCCESS;
                });
    }

    public int runIndexStatus(String[] arguments, Appendable output, Appendable error) throws IOException {
        return CliCommandSupport.run(arguments, output, error, STATUS_USAGE, Options::singleProject,
                CliCommandSupport.reportingCause("index-status"), options -> {
                    ProjectOperations.ProjectView project = operations.inspectProject(options.project());
                    output.append(renderIndexStatus(project, options.format())).append('\n');
                    return FindSymbolCommand.SUCCESS;
                });
    }

    public static String usage() {
        return USAGE;
    }

    /** Usage de l'alias {@code inspect}. */
    static String inspectUsage() {
        return INSPECT_USAGE;
    }

    /** Usage de {@code index-status}. */
    static String indexStatusUsage() {
        return STATUS_USAGE;
    }

    private int runAdd(String[] arguments, Appendable output, Appendable error) throws IOException {
        return CliCommandSupport.run(arguments, output, error, ADD_USAGE, AddOptions::parse,
                CliCommandSupport.reportingCause("project add"), options -> {
                    ProjectOperations.ProjectView project = operations.addProject(options.path(), options.name());
                    output.append(renderProject(project, options.format())).append('\n');
                    return FindSymbolCommand.SUCCESS;
                });
    }

    private int runList(String[] arguments, Appendable output, Appendable error) throws IOException {
        return CliCommandSupport.run(arguments, output, error, LIST_USAGE,
                listArguments -> FORMAT_ONLY.parse(listArguments, 0).format(),
                CliCommandSupport.reportingCause("project list"), format -> {
                    List<ProjectOperations.ProjectView> projects = operations.listProjects();
                    output.append(renderProjects(projects, format)).append('\n');
                    return FindSymbolCommand.SUCCESS;
                });
    }

    private static String renderProjects(List<ProjectOperations.ProjectView> projects, SymbolOutputFormat format) {
        if (format == SymbolOutputFormat.JSON) {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("count", projects.size());
            root.put("projects", projects.stream().map(ProjectJson::project).toList());
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
            return CliJson.render(ProjectJson.project(project));
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

    private String renderIndexStatus(ProjectOperations.ProjectView project, SymbolOutputFormat format) {
        Optional<ResumableRunSummary> resumable = resumeStatus.resumableRun(project.id());
        Instant now = Instant.now();
        Map<String, Object> status = ProjectJson.indexStatus(project, resumable, now);
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
                "providerVersion: " + nullable(project.providerVersion()),
                "resumableRunId: " + resumable.map(summary -> summary.runId().toString()).orElse("none"),
                "resumableRunPhase: " + resumable.map(summary -> summary.phase().name()).orElse("none"),
                "resumableCheckpointAgeSeconds: "
                        + resumable.map(summary -> Long.toString(summary.checkpointAgeSeconds(now))).orElse("none"),
                "resumableTargets: " + resumable
                        .map(summary -> summary.resumableTargets() + "/" + summary.completedExecutions())
                        .orElse("none")
        );
    }

    private static int usageError(String message, Appendable error) throws IOException {
        error.append("error: ").append(message).append('\n').append(USAGE).append('\n');
        return FindSymbolCommand.USAGE_ERROR;
    }

    private static String nullable(String value) {
        return value == null ? "-" : value;
    }

    private static String[] slice(String[] values, int from) {
        return java.util.Arrays.copyOfRange(values, from, values.length);
    }

    private record Options(String project, SymbolOutputFormat format) {
        private static Options singleProject(String[] arguments) {
            if (arguments.length < 1) {
                throw new IllegalArgumentException("expected <project>");
            }
            String project = CliCommandSupport.operand(arguments[0], "project");
            return new Options(project, FORMAT_ONLY.parse(arguments, 1).format());
        }
    }

    private record AddOptions(Path path, String name, SymbolOutputFormat format) {
        private static AddOptions parse(String[] arguments) {
            if (arguments.length < 1) {
                throw new IllegalArgumentException("expected <path>");
            }
            Path path = Path.of(CliCommandSupport.operand(arguments[0], "path"));
            CliOptions options = ADD_OPTIONS.parse(arguments, 1);
            return new AddOptions(path, options.text("--name", defaultName(path)), options.format());
        }

        private static String defaultName(Path path) {
            Path fileName = path.toAbsolutePath().normalize().getFileName();
            return fileName == null ? "project" : fileName.toString();
        }
    }
}
