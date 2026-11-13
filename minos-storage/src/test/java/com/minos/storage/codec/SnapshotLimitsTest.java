package com.minos.storage.codec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The three limits come from one value, and the cache ceiling must cover the largest persistable snapshot. */
class SnapshotLimitsTest {

    @Test
    void theProductionCacheCeilingCoversTheLargestPersistableSnapshot() {
        SnapshotLimits production = SnapshotLimits.production();

        assertDoesNotThrow(production::requireCacheCoversPersistable);
        assertTrue(production.maxQueryCacheWeightBytes() >= production.largestPersistableViewWeightBytes(),
                "cache " + production.maxQueryCacheWeightBytes() + " < " + production.largestPersistableViewWeightBytes());
    }

    @Test
    void aCacheCeilingBelowThePersistableViewWeightIsRefusedNamingTheThreeValues() {
        SnapshotLimits production = SnapshotLimits.production();
        SnapshotLimits reduced = production.withMaxQueryCacheWeightBytes(production.maxPersistedBytes());

        IllegalStateException refused = assertThrows(IllegalStateException.class, reduced::requireCacheCoversPersistable);

        assertTrue(refused.getMessage().contains(String.valueOf(reduced.maxQueryCacheWeightBytes())), refused.getMessage());
        assertTrue(refused.getMessage().contains(String.valueOf(reduced.maxPersistedBytes())), refused.getMessage());
        assertTrue(refused.getMessage().contains(String.valueOf(reduced.currentFormatAmplificationPercent())),
                refused.getMessage());
    }

    @Test
    void theCacheCeilingMayEqualThePersistableViewWeightButNotFallBelowIt() {
        SnapshotLimits production = SnapshotLimits.production();
        long weight = production.largestPersistableViewWeightBytes();

        assertDoesNotThrow(production.withMaxQueryCacheWeightBytes(weight)::requireCacheCoversPersistable);
        assertThrows(IllegalStateException.class,
                production.withMaxQueryCacheWeightBytes(weight - 1L)::requireCacheCoversPersistable);
    }

    @Test
    void theAmplifiedWeightRoundsUpAndSaturates() {
        SnapshotLimits limits = new SnapshotLimits(1024L, 1024L, 125L, 200L);

        assertEquals(0L, limits.amplifiedBytes(3, -5L));
        assertEquals(125L, limits.amplifiedBytes(3, 100L));
        assertEquals(2L, limits.amplifiedBytes(3, 1L), "rounded up: an estimate is never below its exact value");
        assertEquals(Long.MAX_VALUE, limits.amplifiedBytes(3, Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, limits.amplifiedBytes(1, Long.MAX_VALUE));
    }

    @Test
    void onlyTheCurrentFormatUsesTheMeasuredAmplification() {
        SnapshotLimits production = SnapshotLimits.production();

        assertEquals(125L, production.amplificationPercent(3));
        assertEquals(200L, production.amplificationPercent(2));
        assertEquals(200L, production.amplificationPercent(1));
        assertEquals(1_250L, production.amplifiedBytes(3, 1_000L));
        assertEquals(2_000L, production.amplifiedBytes(2, 1_000L));
        assertEquals(2_000L, production.amplifiedBytes(1, 1_000L));
    }

    @Test
    void theProductionValuesAreTheDecidedOnes() {
        SnapshotLimits production = SnapshotLimits.production();

        assertEquals(400L * 1024L * 1024L, production.maxPersistedBytes());
        assertEquals(512L * 1024L * 1024L, production.maxQueryCacheWeightBytes());
        assertEquals(500L * 1024L * 1024L, production.largestPersistableViewWeightBytes());
    }

    @Test
    void anEffectiveCeilingBelowTheInvariantUnderASmallHeapLeavesTheInvariantOnTheConfiguredCeiling() {
        SnapshotLimits production = SnapshotLimits.production();
        QueryViewMemoryBudget small = QueryViewMemoryBudget.of(512L * 1024L * 1024L, 50, SnapshotLimits.VIEW_ADMISSION_WAIT);

        long effective = small.effectiveCacheCeiling(production.maxQueryCacheWeightBytes());

        assertEquals(small.budgetBytes(), effective);
        assertTrue(effective < production.largestPersistableViewWeightBytes(), "the effective ceiling is below the invariant");
        assertDoesNotThrow(production::requireCacheCoversPersistable, "the invariant holds on the configured ceiling");
    }

    @Test
    void theFetchTransientIsTheMeasuredAmplificationOfThePayloadRoundedUpAndSaturated() {
        SnapshotLimits production = SnapshotLimits.production();

        assertEquals(310L, SnapshotLimits.POSTGRES_FETCH_AMPLIFICATION_PERCENT);
        assertEquals(3_100L, production.fetchTransientBytes(1_000L));
        assertEquals(4L, production.fetchTransientBytes(1L));
        assertEquals(0L, production.fetchTransientBytes(-1L));
        assertEquals(Long.MAX_VALUE, production.fetchTransientBytes(Long.MAX_VALUE));
    }

    @Test
    void aStoredSizeOutsideThePersistedCeilingIsRefusedWithSizesOnly() throws Exception {
        SnapshotLimits limits = SnapshotLimits.production().withMaxPersistedBytes(100L);

        limits.requirePersistedSize(1L);
        limits.requirePersistedSize(100L);
        java.io.IOException above = assertThrows(java.io.IOException.class, () -> limits.requirePersistedSize(101L));
        assertEquals("snapshot payload exceeds persisted byte limit: 101/100", above.getMessage());
        assertThrows(java.io.IOException.class, () -> limits.requirePersistedSize(0L));
    }

    @Test
    void aLimitBelowItsMinimumIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new SnapshotLimits(0L, 1L, 100L, 100L));
        assertThrows(IllegalArgumentException.class, () -> new SnapshotLimits(1L, 0L, 100L, 100L));
        assertThrows(IllegalArgumentException.class, () -> new SnapshotLimits(1L, 1L, 99L, 100L));
        assertThrows(IllegalArgumentException.class, () -> new SnapshotLimits(1L, 1L, 100L, 99L));
    }
}
