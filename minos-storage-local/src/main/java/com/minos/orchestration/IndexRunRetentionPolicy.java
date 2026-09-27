package com.minos.orchestration;

/** Count-based retention policy for persisted indexing runs. */
public record IndexRunRetentionPolicy(int maxSucceededRuns, int maxNonSucceededRuns) {

    public static final int DEFAULT_MAX_SUCCEEDED_RUNS = 20;
    public static final int DEFAULT_MAX_NON_SUCCEEDED_RUNS = 10;

    public IndexRunRetentionPolicy {
        if (maxSucceededRuns < 0) {
            throw new IllegalArgumentException("maxSucceededRuns must not be negative");
        }
        if (maxNonSucceededRuns < 0) {
            throw new IllegalArgumentException("maxNonSucceededRuns must not be negative");
        }
    }

    public static IndexRunRetentionPolicy defaults() {
        return new IndexRunRetentionPolicy(
                DEFAULT_MAX_SUCCEEDED_RUNS,
                DEFAULT_MAX_NON_SUCCEEDED_RUNS
        );
    }
}
