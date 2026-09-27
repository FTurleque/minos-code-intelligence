package com.minos.git;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Port : intelligence Git factuelle d'un dépôt local (M12). La couche application et les surfaces
 * n'en connaissent que ce contrat ; l'adaptateur JGit ({@code GitIntelligenceService},
 * minos-integration-git) l'implémente.
 *
 * <p>Les méthodes déclarent {@code throws Exception} : un adaptateur propage tel quel l'échec de sa
 * bibliothèque Git (par exemple une exception contrôlée JGit), exactement comme avant l'extraction
 * du port, pour que les messages d'erreur publics restent identiques.</p>
 */
public interface GitIntelligence {

    RepositoryView inspect(Path projectRoot) throws Exception;

    ActivityReport analyze(Path projectRoot, ActivityQuery query) throws Exception;

    record ActivityQuery(Instant since, int maxCommits, int maxFiles, int zoneDepth) {
        private static final int MAX_COMMITS = 10_000;
        private static final int MAX_FILES = 10_000;
        private static final int MAX_ZONE_DEPTH = 8;

        public ActivityQuery {
            Objects.requireNonNull(since, "since");
            if (maxCommits < 1 || maxCommits > MAX_COMMITS) {
                throw new IllegalArgumentException("maxCommits must be between 1 and " + MAX_COMMITS);
            }
            if (maxFiles < 1 || maxFiles > MAX_FILES) {
                throw new IllegalArgumentException("maxFiles must be between 1 and " + MAX_FILES);
            }
            if (zoneDepth < 1 || zoneDepth > MAX_ZONE_DEPTH) {
                throw new IllegalArgumentException("zoneDepth must be between 1 and " + MAX_ZONE_DEPTH);
            }
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
            ActivityQuery query,
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
