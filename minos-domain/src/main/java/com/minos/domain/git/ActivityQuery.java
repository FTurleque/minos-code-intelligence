package com.minos.domain.git;

import java.time.Instant;
import java.util.Objects;

/**
 * Bounds of a factual Git activity analysis (ADR 0019).
 *
 * @param includeAuthorEmails true only on explicit request: the real address of each author is then rendered in
 *                            {@link CommitActivity#authorEmail()}; false, it never enters the report (the count of
 *                            distinct authors still relies on it)
 */
public record ActivityQuery(Instant since, int maxCommits, int maxFiles, int zoneDepth, boolean includeAuthorEmails) {
    private static final int MAX_COMMITS = 10_000;
    private static final int MAX_FILES = 10_000;
    private static final int MAX_ZONE_DEPTH = 8;

    /** Query without author addresses, the default. */
    public ActivityQuery(Instant since, int maxCommits, int maxFiles, int zoneDepth) {
        this(since, maxCommits, maxFiles, zoneDepth, false);
    }

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
