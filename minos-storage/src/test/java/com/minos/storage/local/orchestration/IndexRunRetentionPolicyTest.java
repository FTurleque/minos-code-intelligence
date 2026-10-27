package com.minos.storage.local.orchestration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IndexRunRetentionPolicyTest {

    @Test
    void refusesANegativeSucceededAllowance() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new IndexRunRetentionPolicy(-1, 0));
        assertEquals("maxSucceededRuns must not be negative", failure.getMessage());
    }

    @Test
    void refusesANegativeNonSucceededAllowance() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new IndexRunRetentionPolicy(0, -1));
        assertEquals("maxNonSucceededRuns must not be negative", failure.getMessage());
    }

    @Test
    void acceptsZeroAsTheKeepNothingBoundary() {
        IndexRunRetentionPolicy policy = new IndexRunRetentionPolicy(0, 0);
        assertEquals(0, policy.maxSucceededRuns());
        assertEquals(0, policy.maxNonSucceededRuns());
    }

    @Test
    void defaultsKeepTwentySucceededAndTenOtherRuns() {
        IndexRunRetentionPolicy defaults = IndexRunRetentionPolicy.defaults();
        assertEquals(new IndexRunRetentionPolicy(20, 10), defaults);
        assertEquals(IndexRunRetentionPolicy.DEFAULT_MAX_SUCCEEDED_RUNS, defaults.maxSucceededRuns());
        assertEquals(IndexRunRetentionPolicy.DEFAULT_MAX_NON_SUCCEEDED_RUNS, defaults.maxNonSucceededRuns());
    }
}
