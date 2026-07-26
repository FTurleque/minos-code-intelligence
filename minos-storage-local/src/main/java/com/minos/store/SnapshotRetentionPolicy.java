package com.minos.store;

/**
 * Count-based retention policy for persisted code-knowledge snapshots.
 *
 * <p>The active snapshot is always retained separately from this historical allowance.</p>
 */
public record SnapshotRetentionPolicy(int maxHistoricalSnapshots) {

    public static final int DEFAULT_MAX_HISTORICAL_SNAPSHOTS = 2;

    public SnapshotRetentionPolicy {
        if (maxHistoricalSnapshots < 0) {
            throw new IllegalArgumentException("maxHistoricalSnapshots must not be negative");
        }
    }

    public static SnapshotRetentionPolicy defaults() {
        return new SnapshotRetentionPolicy(DEFAULT_MAX_HISTORICAL_SNAPSHOTS);
    }
}
