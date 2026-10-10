package com.minos.cli;

import com.minos.application.ProjectOperations;
import com.minos.git.GitIntelligence;
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

import static com.minos.output.json.DeterministicJson.object;

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

    private static final CliOptions.Spec OPTIONS = CliOptions.spec()
            .text("--format")
            .integer("--days", 1, 3650)
            .integer("--max-commits", 1, 10_000)
            .integer("--max-files", 1, 10_000)
            .integer("--zone-depth", 1, 8);

    private final ProjectOperations projects;
    private final GitIntelligence git;

    public GitActivityCommand(ProjectOperations projects, GitIntelligence git) {
        this.projects = Objects.requireNonNull(projects, "projects");
        this.git = Objects.requireNonNull(git, "git");
    }

    public int run(String[] arguments, Appendable output, Appendable error) throws IOException {
        return CliCommandSupport.run(arguments, output, error, USAGE, Options::parse,
                (options, exception) -> NAME + " failed: " + failureMessage(exception),
                options -> {
                    ProjectOperations.ProjectView project = projects.inspectProject(options.project());
                    GitIntelligence.ActivityReport report = git.analyze(
                            Path.of(project.rootPath()),
                            new GitIntelligence.ActivityQuery(
                                    Instant.now().minus(Duration.ofDays(options.days())),
                                    options.maxCommits(),
                                    options.maxFiles(),
                                    options.zoneDepth()
                            )
                    );
                    output.append(render(report, options.format())).append('\n');
                    return FindSymbolCommand.SUCCESS;
                });
    }

    public static String usage() {
        return USAGE;
    }

    static String render(GitIntelligence.ActivityReport report, SymbolOutputFormat format) {
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
        for (GitIntelligence.ZoneActivity zone : report.zones()) {
            lines.add("zone\t" + zone.zone() + "\t" + zone.commitTouches() + "\t" + zone.distinctFileCount()
                    + "\t" + zone.lastChangedAt());
        }
        return String.join("\n", lines);
    }

    private static Map<String, Object> reportMap(GitIntelligence.ActivityReport report) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("nature", "FACTUAL_ACTIVITY");
        root.put("importanceInference", false);
        root.put("repository", repositoryMap(report.repository()));
        root.put("query", object(
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

    private static Map<String, Object> repositoryMap(GitIntelligence.RepositoryView repository) {
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

    private static Map<String, Object> commitMap(GitIntelligence.CommitActivity commit) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("commitId", commit.commitId());
        map.put("committedAt", commit.committedAt().toString());
        map.put("authorName", commit.authorName());
        map.put("authorEmail", commit.authorEmail());
        map.put("message", commit.message());
        map.put("changedPaths", commit.changedPaths());
        return map;
    }

    private static Map<String, Object> fileMap(GitIntelligence.FileActivity file) {
        return object(
                "path", file.path(),
                "commitCount", file.commitCount(),
                "uniqueAuthorCount", file.uniqueAuthorCount(),
                "lastChangedAt", file.lastChangedAt().toString(),
                "lastCommitId", file.lastCommitId()
        );
    }

    private static Map<String, Object> zoneMap(GitIntelligence.ZoneActivity zone) {
        return object(
                "zone", zone.zone(),
                "commitTouches", zone.commitTouches(),
                "distinctFileCount", zone.distinctFileCount(),
                "lastChangedAt", zone.lastChangedAt().toString()
        );
    }

    /** Reports the immediate cause of a runtime wrapper: git plumbing rethrows once, never deeper. */
    private static String failureMessage(Exception exception) {
        return CliCommandSupport.failureMessage(
                exception instanceof RuntimeException && exception.getCause() != null
                        ? exception.getCause()
                        : exception);
    }

    private static String nullable(String value) {
        return value == null ? "-" : value;
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
            if (arguments.length < 1) {
                throw new IllegalArgumentException("expected <project>");
            }
            String project = CliCommandSupport.operand(arguments[0], "project");
            CliOptions options = OPTIONS.parse(arguments, 1);
            return new Options(project, options.integer("--days", 30), options.integer("--max-commits", 500),
                    options.integer("--max-files", 500), options.integer("--zone-depth", 2), options.format());
        }
    }
}
