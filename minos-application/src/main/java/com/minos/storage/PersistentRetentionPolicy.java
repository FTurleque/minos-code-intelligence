package com.minos.storage;

/** Backend-neutral product retention policy for durable indexing state. */
public record PersistentRetentionPolicy(
        int maxHistoricalSnapshots,
        int maxSucceededRuns,
        int maxNonSucceededRuns
) {
    public static final int DEFAULT_MAX_HISTORICAL_SNAPSHOTS = 2;
    public static final int DEFAULT_MAX_SUCCEEDED_RUNS = 20;
    public static final int DEFAULT_MAX_NON_SUCCEEDED_RUNS = 10;

    public static final PersistentRetentionPolicy DEFAULT = new PersistentRetentionPolicy(
            DEFAULT_MAX_HISTORICAL_SNAPSHOTS,
            DEFAULT_MAX_SUCCEEDED_RUNS,
            DEFAULT_MAX_NON_SUCCEEDED_RUNS);

    public PersistentRetentionPolicy {
        if (maxHistoricalSnapshots < 0 || maxSucceededRuns < 0 || maxNonSucceededRuns < 0) {
            throw new IllegalArgumentException("persistent retention limits must not be negative");
        }
    }
}
