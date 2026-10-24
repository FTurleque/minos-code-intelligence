package com.minos.store.cache;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bounds, weight accounting, refusals and concurrent use of the shared weighted LRU cache. */
class WeightedLruCacheTest {

    @Test
    void evictsLeastRecentlyReadEntriesUntilTheWeightBoundHolds() {
        WeightedLruCache<String, String> cache = new WeightedLruCache<>(10, 100L);
        assertTrue(cache.put("a", "A", 40L));
        assertTrue(cache.put("b", "B", 40L));
        assertEquals("A", cache.get("a"), "reading a makes b the least recently read entry");

        assertTrue(cache.put("c", "C", 40L));

        assertNull(cache.get("b"));
        assertEquals("A", cache.get("a"));
        assertEquals("C", cache.get("c"));
        assertEquals(80L, cache.weight());
        assertEquals(2, cache.size());
        assertEquals(1L, cache.evictions());
    }

    @Test
    void evictsLeastRecentlyReadEntriesUntilTheEntryBoundHolds() {
        WeightedLruCache<String, String> cache = new WeightedLruCache<>(2, 1_000L);
        cache.put("a", "A", 1L);
        cache.put("b", "B", 2L);
        cache.get("a");
        cache.put("c", "C", 3L);
        cache.put("d", "D", 4L);

        assertNull(cache.get("b"));
        assertNull(cache.get("a"));
        assertEquals("C", cache.get("c"));
        assertEquals("D", cache.get("d"));
        assertEquals(7L, cache.weight());
        assertEquals(2L, cache.evictions());
        assertEquals(2, cache.maximumEntries());
        assertEquals(1_000L, cache.maximumWeight());
    }

    @Test
    void weightReturnsToZeroAfterRemovalsReplacementsAndInvalidations() {
        WeightedLruCache<String, String> cache = new WeightedLruCache<>(16, 1_000L);
        cache.put("p1:a", "A", 10L);
        cache.put("p1:b", "B", 20L);
        cache.put("p2:a", "C", 30L);
        cache.put("p2:b", "D", 40L);
        cache.put("p1:a", "A2", 15L);
        assertEquals(105L, cache.weight(), "a replacement subtracts the weight of the previous value");
        assertEquals("A2", cache.get("p1:a"));

        assertEquals(2, cache.removeIf(key -> key.startsWith("p1:")));
        assertEquals(70L, cache.weight());
        assertEquals("C", cache.remove("p2:a"));
        assertNull(cache.remove("p2:a"), "a second removal finds nothing and changes nothing");
        assertEquals(40L, cache.weight());
        assertEquals(0, cache.removeIf(key -> key.startsWith("p1:")));
        assertEquals("D", cache.remove("p2:b"));

        assertEquals(0L, cache.weight());
        assertEquals(0, cache.size());
        assertEquals(0L, cache.evictions(), "removals and invalidations are not evictions");
    }

    @Test
    void entryHeavierThanTheMaximumIsNeitherKeptNorEvictsAnything() {
        WeightedLruCache<String, String> cache = new WeightedLruCache<>(4, 100L);
        cache.put("a", "A", 30L);
        cache.put("b", "B", 30L);
        cache.put("heavy", "old", 20L);

        assertFalse(cache.put("heavy", "new", 101L));

        assertNull(cache.get("heavy"), "the previous value of the same key is removed");
        assertEquals("A", cache.get("a"));
        assertEquals("B", cache.get("b"));
        assertEquals(60L, cache.weight());
        assertEquals(0L, cache.evictions());
        assertTrue(cache.put("exact", "fits", 40L), "an entry that reaches the maximum exactly is kept");
        assertEquals(100L, cache.weight());
    }

    @Test
    void negativeWeightIsRefusedWithoutChangingTheCache() {
        WeightedLruCache<String, String> cache = new WeightedLruCache<>(4, 100L);
        cache.put("a", "A", 30L);
        cache.put("b", "B", 30L);

        assertThrows(IllegalArgumentException.class, () -> cache.put("a", "replacement", -1L));
        assertThrows(IllegalArgumentException.class, () -> cache.put("c", "C", Long.MIN_VALUE));

        assertEquals("A", cache.get("a"));
        assertEquals("B", cache.get("b"));
        assertNull(cache.get("c"));
        assertEquals(2, cache.size());
        assertEquals(60L, cache.weight());
    }

    @Test
    void invalidBoundsAreRefusedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new WeightedLruCache<String, String>(0, 100L));
        assertThrows(IllegalArgumentException.class, () -> new WeightedLruCache<String, String>(-1, 100L));
        assertThrows(IllegalArgumentException.class, () -> new WeightedLruCache<String, String>(1, 0L));
        assertThrows(IllegalArgumentException.class, () -> new WeightedLruCache<String, String>(1, -1L));
        assertThrows(IllegalArgumentException.class,
                () -> new WeightedLruCache<String, String>(1, WeightedLruCache.MAXIMUM_WEIGHT_LIMIT + 1L));
        assertThrows(IllegalArgumentException.class, () -> new WeightedLruCache<String, String>(1, Long.MAX_VALUE));

        WeightedLruCache<String, String> widest =
                new WeightedLruCache<>(Integer.MAX_VALUE, WeightedLruCache.MAXIMUM_WEIGHT_LIMIT);
        assertTrue(widest.put("a", "A", WeightedLruCache.MAXIMUM_WEIGHT_LIMIT));
        assertTrue(widest.put("b", "B", WeightedLruCache.MAXIMUM_WEIGHT_LIMIT), "the sum of two maxima cannot overflow");
        assertEquals(WeightedLruCache.MAXIMUM_WEIGHT_LIMIT, widest.weight());
        assertEquals(1L, widest.evictions());
    }

    @Test
    void nullArgumentsAreRefused() {
        WeightedLruCache<String, String> cache = new WeightedLruCache<>(4, 100L);
        assertThrows(NullPointerException.class, () -> cache.get(null));
        assertThrows(NullPointerException.class, () -> cache.put(null, "A", 1L));
        assertThrows(NullPointerException.class, () -> cache.put("a", null, 1L));
        assertThrows(NullPointerException.class, () -> cache.remove(null));
        assertThrows(NullPointerException.class, () -> cache.removeIf(null));
        assertEquals(0, cache.size());
    }

    /**
     * Threads released together by a barrier read, insert, replace and invalidate overlapping keys. Each key has one
     * fixed weight, so the expected total is recomputed from the entries still present once every thread is done.
     */
    @Test
    void concurrentReadsAndWritesKeepTheBoundsAndTheWeightConsistent() throws Exception {
        int threads = 8;
        int keys = 40;
        int rounds = 2_000;
        long maximumWeight = 300L;
        int maximumEntries = 12;
        WeightedLruCache<Integer, String> cache = new WeightedLruCache<>(maximumEntries, maximumWeight);
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                int seed = thread;
                results.add(executor.submit(() -> {
                    start.await(30, TimeUnit.SECONDS);
                    int violations = 0;
                    for (int round = 0; round < rounds; round++) {
                        int key = Math.floorMod(seed * 7919 + round * 31, keys);
                        switch (round % 5) {
                            case 0, 1 -> cache.put(key, "v" + key, weightOf(key));
                            case 2 -> cache.get(key);
                            case 3 -> cache.remove(key);
                            default -> cache.removeIf(candidate -> candidate % 11 == seed);
                        }
                        if (cache.size() > maximumEntries || cache.weight() > maximumWeight || cache.weight() < 0L) {
                            violations++;
                        }
                    }
                    return violations;
                }));
            }
            for (Future<Integer> result : results) assertEquals(0, result.get(60, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        long expected = 0L;
        int present = 0;
        for (int key = 0; key < keys; key++) {
            String value = cache.get(key);
            if (value != null) {
                assertEquals("v" + key, value);
                expected += weightOf(key);
                present++;
            }
        }
        assertEquals(expected, cache.weight(), "the total weight is the sum of the weights of the present entries");
        assertEquals(present, cache.size());
        assertTrue(cache.size() <= maximumEntries);
        assertTrue(cache.weight() <= maximumWeight);
    }

    private static long weightOf(int key) {
        return 5L + key % 7 * 10L;
    }
}
