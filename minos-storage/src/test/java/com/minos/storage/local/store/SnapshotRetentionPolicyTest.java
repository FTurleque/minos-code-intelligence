package com.minos.storage.local.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SnapshotRetentionPolicyTest {

    @Test
    void refusesANegativeHistoricalAllowance() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new SnapshotRetentionPolicy(-1));
        assertEquals("maxHistoricalSnapshots must not be negative", failure.getMessage());
    }

    @Test
    void acceptsZeroToKeepOnlyTheActiveSnapshot() {
        assertEquals(0, new SnapshotRetentionPolicy(0).maxHistoricalSnapshots());
    }

    @Test
    void defaultsKeepTwoHistoricalSnapshots() {
        assertEquals(new SnapshotRetentionPolicy(SnapshotRetentionPolicy.DEFAULT_MAX_HISTORICAL_SNAPSHOTS),
                SnapshotRetentionPolicy.defaults());
        assertEquals(2, SnapshotRetentionPolicy.defaults().maxHistoricalSnapshots());
    }
}
