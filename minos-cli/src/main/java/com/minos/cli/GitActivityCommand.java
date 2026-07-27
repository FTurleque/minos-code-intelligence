package com.minos.cli;

import com.minos.git.GitIntelligenceService;
import com.minos.output.SymbolOutputFormat;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** CLI adapter exposing the existing factual Git intelligence to external IDE clients. */
public final class GitActivityCommand {

    public static final String NAME = "git-activity";
    private static final String USAGE = """
            Usage: minos git-activity <project> [options]

            Options:
              --days <1..3650>          History window in days (default: 30)
              --max-commits <1..10000>  Maximum commits scanned (default: 500)
              --max-files <1..10000>    Maximum files/zones returned (default: 500)
              --zone-depth <1..8>       Directory depth used for zones (default: 2)
              --format <text|json>      Output format (default: text)
            """.stripTrailing();

    private final ProjectOperations projects;
    private final GitIntelligenceService git;

    public GitActivityCommand(ProjectOperations projects, GitIntelligenceService git) {
        this.projects = Objects.requireNonNull(projects, "projects");
        this.git = Objects.requireNonNull(git, "git");
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
            error.append("error: ").append(exception.getMessage()).append('\n').append(USAGE).append('\n');
            return FindSymbolCommand.USAGE_ERROR;
        }

        try {
            ProjectOperations.ProjectView project = projects.inspectProject(options.project());
            GitIntelligenceService.ActivityReport report = git.analyze(
                    Path.of(project.rootPath()),
                    new GitIntelligenceService.ActivityQuery(
                            Instant.now().minus(Duration.ofDays(options.days())),
                            options.maxCommits(),
                            options.maxFiles(),
                            options.zoneDepth()
                    )
            );
            output.append(render(report, options.format())).append('\n');
            return FindSymbolCommand.SUCCESS;
        } catch (Exception exception) {
            error.append("error: git-activity failed: ").append(failureMessage(exception)).append('\n');
            return FindSymbolCommand.EXECUTION_ERROR;
        }
    }

    public static String usage() {
        return USAGE;
    }

    static String render(GitIntelligenceService.ActivityReport report, SymbolOutputFormat format) {
        if (format == SymbolOutputFormat.JSON) {
            return CliJson.render(reportMap(report));
        }
        List<String> lines = new ArrayList<>();
        lines.add("branch: " + nullable(report.repository().branch()));
        lines.add("head: " + nullable(report.repository().headCommit()));
        lines.add("clean: " + report.repository().clean());
        lines.add("scannedCommits: " + report.scannedCommitCount());
        lines.add("files: " + report.files().size());
        lines.add("zones: " + report.zones().size());
        lines.add("limitations: " + String.join(",", report.limitations()));
        lines.add("note: activity is factual and is not architectural or business importance");
        for (GitIntelligenceService.ZoneActivity zone : report.zones()) {
            lines.add("zone\t" + zone.zone() + "\t" + zone.commitTouches() + "\t" + zone.distinctFileCount()
                    + "\t" + zone.lastChangedAt());
        }
        return String.join("\n", lines);
    }

    private static Map<String, Object> reportMap(GitIntelligenceService.ActivityReport report) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("nature", "FACTUAL_ACTIVITY");
        root.put("importanceInference", false);
        root.put("repository", repositoryMap(report.repository()));
        root.put("query", Map.of(
                "since", report.query().since().toString(),
                "maxCommits", report.query().maxCommits(),
                "maxFiles", report.query().maxFiles(),
                "zoneDepth", report.query().zoneDepth()
        ));
        root.put("scannedCommitCount", report.scannedCommitCount());
        root.put("historyTruncated", report.historyTruncated());
        root.put("filesTruncated", report.filesTruncated());
        root.put("recentCommits", report.recentCommits().stream().map(GitActivityCommand::commitMap).toList());
        root.put("files", report.files().stream().map(GitActivityCommand::fileMap).toList());
        root.put("zones", report.zones().stream().map(GitActivityCommand::zoneMap).toList());
        root.put("limitations", report.limitations());
        return root;
    }

    private static Map<String, Object> repositoryMap(GitIntelligenceService.RepositoryView repository) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("repositoryId", repository.repositoryId());
        map.put("workTree", repository.workTree());
        map.put("originRemote", repository.originRemote());
        map.put("branch", repository.branch());
        map.put("headCommit", repository.headCommit());
        map.put("detachedHead", repository.detachedHead());
        map.put("shallow", repository.shallow());
        map.put("clean", repository.clean());
        map.put("limitations", repository.limitations());
        return map;
    }

    private static Map<String, Object> commitMap(GitIntelligenceService.CommitActivity commit) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("commitId", commit.commitId());
        map.put("committedAt", commit.committedAt().toString());
        map.put("authorName", commit.authorName());
        map.put("authorEmail", commit.authorEmail());
        map.put("message", commit.message());
        map.put("changedPaths", commit.changedPaths());
        return map;
    }

    private static Map<String, Object> fileMap(GitIntelligenceService.FileActivity file) {
        return Map.of(
                "path", file.path(),
                "commitCount", file.commitCount(),
                "uniqueAuthorCount", file.uniqueAuthorCount(),
                "lastChangedAt", file.lastChangedAt().toString(),
                "lastCommitId", file.lastCommitId()
        );
    }

    private static Map<String, Object> zoneMap(GitIntelligenceService.ZoneActivity zone) {
        return Map.of(
                "zone", zone.zone(),
                "commitTouches", zone.commitTouches(),
                "distinctFileCount", zone.distinctFileCount(),
                "lastChangedAt", zone.lastChangedAt().toString()
        );
    }

    private static String failureMessage(Exception exception) {
        Throwable effective = exception instanceof RuntimeException && exception.getCause() != null
                ? exception.getCause()
                : exception;
        String message = effective.getMessage();
        return message == null || message.isBlank()
                ? effective.getClass().getSimpleName()
                : message.replace('\r', ' ').replace('\n', ' ');
    }

    private static String nullable(String value) {
        return value == null ? "-" : value;
    }

    private static boolean isHelp(String value) {
        return "--help".equals(value) || "-h".equals(value);
    }

    private record Options(
            String project,
            int days,
            int maxCommits,
            int maxFiles,
            int zoneDepth,
            SymbolOutputFormat format
    ) {
        private static Options parse(String[] arguments) {
            if (arguments.length < 1 || arguments[0] == null || arguments[0].isBlank() || arguments[0].startsWith("-")) {
                throw new IllegalArgumentException("expected <project>");
            }
            String project = arguments[0];
            int days = 30;
            int maxCommits = 500;
            int maxFiles = 500;
            int zoneDepth = 2;
            SymbolOutputFormat format = SymbolOutputFormat.TEXT;
            Set<String> seen = new java.util.HashSet<>();
            for (int index = 1; index < arguments.length; index++) {
                String option = arguments[index];
                if (!Set.of("--days", "--max-commits", "--max-files", "--zone-depth", "--format").contains(option)) {
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
                    case "--days" -> days = boundedInt(value, option, 1, 3650);
                    case "--max-commits" -> maxCommits = boundedInt(value, option, 1, 10_000);
                    case "--max-files" -> maxFiles = boundedInt(value, option, 1, 10_000);
                    case "--zone-depth" -> zoneDepth = boundedInt(value, option, 1, 8);
                    case "--format" -> format = SymbolOutputFormat.parse(value);
                    default -> throw new IllegalStateException("unhandled option " + option);
                }
            }
            return new Options(project, days, maxCommits, maxFiles, zoneDepth, format);
        }

        private static int boundedInt(String value, String option, int minimum, int maximum) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed < minimum || parsed > maximum) {
                    throw new IllegalArgumentException(option + " must be between " + minimum + " and " + maximum);
                }
                return parsed;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(option + " must be an integer");
            }
        }
    }
}
