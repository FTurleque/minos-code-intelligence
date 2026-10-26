package com.minos.domain.git;

import java.util.List;

/** Factual Git activity of a work tree: what was scanned, what was truncated, and what could not be read. */
public record ActivityReport(
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
