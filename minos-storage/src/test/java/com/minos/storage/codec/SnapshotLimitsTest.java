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
    void aLimitBelowItsMinimumIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new SnapshotLimits(0L, 1L, 100L, 100L));
        assertThrows(IllegalArgumentException.class, () -> new SnapshotLimits(1L, 0L, 100L, 100L));
        assertThrows(IllegalArgumentException.class, () -> new SnapshotLimits(1L, 1L, 99L, 100L));
        assertThrows(IllegalArgumentException.class, () -> new SnapshotLimits(1L, 1L, 100L, 99L));
    }
}
