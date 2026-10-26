package com.minos.application;

import com.minos.git.GitIntelligence;

import java.nio.file.Path;
import java.util.Objects;

/** Application query over the Git port of the composition: converts its answers, never wraps its failures. */
public final class LocalProjectGitQuery implements ProjectGitQuery {

    private final GitIntelligence git;

    public LocalProjectGitQuery(MinosApplication application) {
        this(Objects.requireNonNull(application, "application").gitIntelligence());
    }

    LocalProjectGitQuery(GitIntelligence git) {
        this.git = Objects.requireNonNull(git, "git");
    }

    @Override
    public RepositoryView inspect(Path projectRoot) throws Exception {
        return repository(git.inspect(projectRoot));
    }

    @Override
    public ActivityReport analyze(Path projectRoot, ActivityRequest request) throws Exception {
        GitIntelligence.ActivityQuery query = Objects.requireNonNull(request, "request").toPort();
        return report(git.analyze(projectRoot, query));
    }

    private static RepositoryView repository(GitIntelligence.RepositoryView value) {
        return new RepositoryView(value.repositoryId(), value.workTree(), value.originRemote(), value.branch(),
                value.headCommit(), value.detachedHead(), value.shallow(), value.clean(), value.limitations());
    }

    private static ActivityReport report(GitIntelligence.ActivityReport value) {
        GitIntelligence.ActivityQuery query = value.query();
        return new ActivityReport(
                repository(value.repository()),
                new ActivityRequest(query.since(), query.maxCommits(), query.maxFiles(), query.zoneDepth(),
                        query.includeAuthorEmails()),
                value.scannedCommitCount(),
                value.historyTruncated(),
                value.filesTruncated(),
                value.recentCommits().stream().map(LocalProjectGitQuery::commit).toList(),
                value.files().stream().map(LocalProjectGitQuery::file).toList(),
                value.zones().stream().map(LocalProjectGitQuery::zone).toList(),
                value.limitations());
    }

    private static CommitActivity commit(GitIntelligence.CommitActivity value) {
        return new CommitActivity(value.commitId(), value.committedAt(), value.authorName(), value.authorEmail(),
                value.message(), value.changedPaths());
    }

    private static FileActivity file(GitIntelligence.FileActivity value) {
        return new FileActivity(value.path(), value.commitCount(), value.uniqueAuthorCount(), value.lastChangedAt(),
                value.lastCommitId());
    }

    private static ZoneActivity zone(GitIntelligence.ZoneActivity value) {
        return new ZoneActivity(value.zone(), value.commitTouches(), value.distinctFileCount(), value.lastChangedAt());
    }
}
