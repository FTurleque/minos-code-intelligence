package com.minos.application;

import com.minos.git.GitIntelligence;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Application query for the factual Git facts of a project's work tree (ADR 0019): what the CLI and the Java API read
 * instead of the engine port. The records carry the same components and values as the port's answers.
 *
 * <p>Both methods declare {@code throws Exception}: the failure of the Git port is propagated unchanged, so the public
 * error messages stay those of the Git library.</p>
 */
public interface ProjectGitQuery {

    RepositoryView inspect(Path projectRoot) throws Exception;

    ActivityReport analyze(Path projectRoot, ActivityRequest request) throws Exception;

    /**
     * Bounds of an activity analysis. They are validated by the port's own query, with its messages.
     *
     * @param includeAuthorEmails true only on explicit request: the real author addresses are then rendered
     */
    record ActivityRequest(Instant since, int maxCommits, int maxFiles, int zoneDepth, boolean includeAuthorEmails) {
        public ActivityRequest {
            toPort(since, maxCommits, maxFiles, zoneDepth, includeAuthorEmails);
        }

        GitIntelligence.ActivityQuery toPort() {
            return toPort(since, maxCommits, maxFiles, zoneDepth, includeAuthorEmails);
        }

        private static GitIntelligence.ActivityQuery toPort(
                Instant since, int maxCommits, int maxFiles, int zoneDepth, boolean includeAuthorEmails) {
            return new GitIntelligence.ActivityQuery(since, maxCommits, maxFiles, zoneDepth, includeAuthorEmails);
        }
    }

    record RepositoryView(
            String repositoryId,
            String workTree,
            String originRemote,
            String branch,
            String headCommit,
            boolean detachedHead,
            boolean shallow,
            boolean clean,
            List<String> limitations
    ) {
        public RepositoryView {
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
        }
    }

    record CommitActivity(
            String commitId,
            Instant committedAt,
            String authorName,
            String authorEmail,
            String message,
            List<String> changedPaths
    ) {
        public CommitActivity {
            changedPaths = changedPaths == null ? List.of() : List.copyOf(changedPaths);
        }
    }

    record FileActivity(
            String path,
            int commitCount,
            int uniqueAuthorCount,
            Instant lastChangedAt,
            String lastCommitId
    ) {
    }

    record ZoneActivity(
            String zone,
            int commitTouches,
            int distinctFileCount,
            Instant lastChangedAt
    ) {
    }

    record ActivityReport(
            RepositoryView repository,
            ActivityRequest request,
            int scannedCommitCount,
            boolean historyTruncated,
            boolean filesTruncated,
            List<CommitActivity> recentCommits,
            List<FileActivity> files,
            List<ZoneActivity> zones,
            List<String> limitations
    ) {
        public ActivityReport {
            recentCommits = recentCommits == null ? List.of() : List.copyOf(recentCommits);
            files = files == null ? List.of() : List.copyOf(files);
            zones = zones == null ? List.of() : List.copyOf(zones);
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
        }
    }
}
