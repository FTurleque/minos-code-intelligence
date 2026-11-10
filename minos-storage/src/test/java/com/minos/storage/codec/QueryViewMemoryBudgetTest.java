package com.minos.storage.codec;

import com.minos.diagnostics.PublicErrorMessages;
import com.minos.diagnostics.SnapshotMemoryExhaustedException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The process-wide memory budget of the snapshot views: a fraction of the maximum heap, shared by the cached views
 * of every registered store and by the loads in progress. A load reserves its estimated weight before it reads a
 * byte; a weight above the budget is refused at once, a weight that does not fit now waits a bounded time.
 */
class QueryViewMemoryBudgetTest {

    private static final long MIB = 1024L * 1024L;
    private static final Duration SHORT = Duration.ofMillis(200);
    private static final Duration LONG = Duration.ofSeconds(30);

    @Test
    void theBudgetIsTheSetFractionOfTheMaximumHeapRoundedDown() {
        assertEquals(500L, QueryViewMemoryBudget.of(1_000L, 50, LONG).budgetBytes());
        assertEquals(300L, QueryViewMemoryBudget.of(1_001L, 30, LONG).budgetBytes());
        assertTrue(QueryViewMemoryBudget.of(Long.MAX_VALUE, 80, LONG).budgetBytes() > 0L, "no overflow");
    }

    @Test
    void underASmallHeapTheEffectiveCacheCeilingIsTheBudget() {
        QueryViewMemoryBudget budget = QueryViewMemoryBudget.of(256L * MIB, 50, LONG);

        assertEquals(128L * MIB, budget.effectiveCacheCeiling(SnapshotLimits.DEFAULT_MAX_QUERY_CACHE_WEIGHT_BYTES));
    }

    @Test
    void underALargeHeapTheEffectiveCacheCeilingIsTheConfiguredOne() {
        QueryViewMemoryBudget budget = QueryViewMemoryBudget.of(4_096L * MIB, 50, LONG);

        assertEquals(SnapshotLimits.DEFAULT_MAX_QUERY_CACHE_WEIGHT_BYTES,
                budget.effectiveCacheCeiling(SnapshotLimits.DEFAULT_MAX_QUERY_CACHE_WEIGHT_BYTES));
    }

    @Test
    void theSystemPropertyComesBeforeTheVariableAndFiftyIsTheDefault() {
        Properties properties = new Properties();
        properties.setProperty(QueryViewMemoryBudget.PROPERTY, "30");

        assertEquals(300L, QueryViewMemoryBudget.resolve(1_000L,
                Map.of(QueryViewMemoryBudget.VARIABLE, "70"), properties, LONG).budgetBytes());
        assertEquals(700L, QueryViewMemoryBudget.resolve(1_000L,
                Map.of(QueryViewMemoryBudget.VARIABLE, " 70 "), new Properties(), LONG).budgetBytes());
        assertEquals(500L, QueryViewMemoryBudget.resolve(1_000L, Map.of(), new Properties(), LONG).budgetBytes());
        assertEquals(30, QueryViewMemoryBudget.resolve(1_000L, Map.of(), properties, LONG).percent());
    }

    @Test
    void anInvalidSettingRefusesEveryLoadNamingTheSettingAndTheRange() {
        for (String invalid : new String[]{"95", "abc", "9", "", "50.5"}) {
            QueryViewMemoryBudget budget = QueryViewMemoryBudget.resolve(1_000L,
                    Map.of(QueryViewMemoryBudget.VARIABLE, invalid), new Properties(), LONG);

            IOException refused = assertThrows(IOException.class, () -> budget.reserve(1L), invalid);
            assertTrue(refused.getMessage().contains("MINOS_VIEW_HEAP_PERCENT"), refused.getMessage());
            assertTrue(refused.getMessage().contains("from 10 to 80"), refused.getMessage());
            assertFalse(refused.getMessage().contains(invalid.isEmpty() ? "\u0000" : "abc"), "the value is not echoed");
            assertEquals(0L, budget.stats().reservedBytes(), "no fallback to the default fraction");
            assertEquals(SnapshotLimits.DEFAULT_MAX_QUERY_CACHE_WEIGHT_BYTES,
                    budget.effectiveCacheCeiling(SnapshotLimits.DEFAULT_MAX_QUERY_CACHE_WEIGHT_BYTES));
        }
    }

    @Test
    void aWeightAboveTheBudgetIsRefusedAtOnceWithAPublicMessage() {
        QueryViewMemoryBudget budget = QueryViewMemoryBudget.of(1_000L, 50, LONG);

        SnapshotMemoryExhaustedException refused =
                assertThrows(SnapshotMemoryExhaustedException.class, () -> budget.reserve(501L));

        assertTrue(refused.getMessage().startsWith("insufficient memory to load this snapshot"), refused.getMessage());
        for (String part : new String[]{"501", "500", "1000", "MINOS_VIEW_HEAP_PERCENT", "-Xmx"}) {
            assertTrue(refused.getMessage().contains(part), part + " in " + refused.getMessage());
        }
        assertFalse(PublicErrorMessages.looksSensitive(refused.getMessage()), refused.getMessage());
        assertEquals(0L, budget.stats().reservedBytes());
        assertEquals(1L, budget.stats().refusals());
        assertEquals(0L, budget.stats().waits(), "refused without waiting");
    }

    @Test
    void aReservationIsReleasedWhenClosedAndClosingTwiceReleasesOnce() throws IOException {
        QueryViewMemoryBudget budget = QueryViewMemoryBudget.of(1_000L, 50, LONG);

        QueryViewMemoryBudget.Reservation first = budget.reserve(300L);
        assertEquals(300L, budget.stats().reservedBytes());
        try (QueryViewMemoryBudget.Reservation second = budget.reserve(200L)) {
            assertEquals(500L, budget.stats().reservedBytes());
            assertEquals(200L, second.bytes());
        }
        first.close();
        first.close();

        assertEquals(0L, budget.stats().reservedBytes());
    }

    @Test
    void anExpiredWaitIsRefusedWithAPublicMessageAndLeavesTheOtherReservation() throws IOException {
        QueryViewMemoryBudget budget = QueryViewMemoryBudget.of(1_000L, 50, SHORT);

        try (QueryViewMemoryBudget.Reservation ignored = budget.reserve(400L)) {
            SnapshotMemoryExhaustedException refused =
                    assertThrows(SnapshotMemoryExhaustedException.class, () -> budget.reserve(200L));

            assertTrue(refused.getMessage().startsWith("insufficient memory to load this snapshot now"),
                    refused.getMessage());
            assertTrue(refused.getMessage().contains("retry later"), refused.getMessage());
            assertFalse(PublicErrorMessages.looksSensitive(refused.getMessage()), refused.getMessage());
            assertEquals(400L, budget.stats().reservedBytes());
            assertEquals(1L, budget.stats().waits());
            assertEquals(1L, budget.stats().refusals());
        }
    }

    @Test
    void aWaitingReservationIsGrantedWhenAnotherOneIsReleased() throws Exception {
        QueryViewMemoryBudget budget = QueryViewMemoryBudget.of(1_000L, 50, LONG);
        QueryViewMemoryBudget.Reservation first = budget.reserve(400L);

        CompletableFuture<QueryViewMemoryBudget.Reservation> second = CompletableFuture.supplyAsync(() -> {
            try {
                return budget.reserve(200L);
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        });
        awaitWaits(budget, 1L);
        assertFalse(second.isDone(), "the second load waits while the first one holds the budget");

        first.close();
        try (QueryViewMemoryBudget.Reservation granted = second.get(10, TimeUnit.SECONDS)) {
            assertEquals(200L, budget.stats().reservedBytes());
            assertEquals(200L, granted.bytes());
        }
    }

    @Test
    void cachedViewsAreEvictedToAdmitALoadWithoutWaiting() throws IOException {
        QueryViewMemoryBudget budget = QueryViewMemoryBudget.of(1_000L, 50, LONG);
        AtomicLong cached = new AtomicLong(400L);
        QueryViewMemoryBudget.Tenant tenant = new QueryViewMemoryBudget.Tenant() {
            @Override public long residentWeightBytes() { return cached.get(); }
            @Override public void evictAll() { cached.set(0L); }
        };
        budget.register(tenant);
        assertEquals(400L, budget.stats().residentBytes());

        try (QueryViewMemoryBudget.Reservation ignored = budget.reserve(300L)) {
            assertEquals(0L, cached.get(), "the cached views were evicted");
            assertEquals(0L, budget.stats().waits());
            assertEquals(300L, budget.stats().reservedBytes());
        }
    }

    @Test
    void cachedViewsAreKeptWhileTheyFitBesideTheLoad() throws IOException {
        QueryViewMemoryBudget budget = QueryViewMemoryBudget.of(1_000L, 50, LONG);
        AtomicLong cached = new AtomicLong(100L);
        QueryViewMemoryBudget.Tenant tenant = new QueryViewMemoryBudget.Tenant() {
            @Override public long residentWeightBytes() { return cached.get(); }
            @Override public void evictAll() { cached.set(0L); }
        };
        budget.register(tenant);

        try (QueryViewMemoryBudget.Reservation ignored = budget.reserve(300L)) {
            assertEquals(100L, cached.get());
        }
    }

    @Test
    void aConvertedReservationBecomesResidentWeightUnderOneLock() throws IOException {
        QueryViewMemoryBudget budget = QueryViewMemoryBudget.of(1_000L, 50, LONG);
        AtomicLong cached = new AtomicLong();
        QueryViewMemoryBudget.Tenant tenant = new QueryViewMemoryBudget.Tenant() {
            @Override public long residentWeightBytes() { return cached.get(); }
            @Override public void evictAll() { cached.set(0L); }
        };
        budget.register(tenant);

        String published;
        try (QueryViewMemoryBudget.Reservation reservation = budget.reserve(300L)) {
            published = budget.convert(reservation, () -> {
                cached.addAndGet(300L);
                return "view";
            });
            assertEquals(0L, budget.stats().reservedBytes(), "converted, not reserved twice");
        }

        assertEquals("view", published);
        assertEquals(300L, budget.stats().residentBytes());
        assertEquals(0L, budget.stats().reservedBytes(), "closing after a conversion releases nothing more");
    }

    @Test
    void aNegativeWeightOrAnInvalidConstructionIsRefused() {
        QueryViewMemoryBudget budget = QueryViewMemoryBudget.of(1_000L, 50, LONG);

        assertThrows(IllegalArgumentException.class, () -> budget.reserve(-1L));
        assertThrows(IllegalArgumentException.class, () -> QueryViewMemoryBudget.of(0L, 50, LONG));
        assertThrows(IllegalArgumentException.class, () -> QueryViewMemoryBudget.of(1_000L, 81, LONG));
        assertThrows(IllegalArgumentException.class, () -> QueryViewMemoryBudget.of(1_000L, 9, LONG));
        assertThrows(IllegalArgumentException.class, () -> QueryViewMemoryBudget.of(1_000L, 50, Duration.ZERO));
    }

    @Test
    void theSharedBudgetIsOnePerProcessAndWaitsThirtySeconds() {
        assertSame(QueryViewMemoryBudget.shared(), QueryViewMemoryBudget.shared());
        assertEquals(Duration.ofSeconds(30), SnapshotLimits.VIEW_ADMISSION_WAIT);
    }

    /** Bounded spin until the budget has counted {@code expected} waits: no sleep, no unbounded loop. */
    private static void awaitWaits(QueryViewMemoryBudget budget, long expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (budget.stats().waits() < expected) {
            if (System.nanoTime() > deadline) throw new AssertionError("no reservation started waiting");
            Thread.onSpinWait();
        }
    }
}
