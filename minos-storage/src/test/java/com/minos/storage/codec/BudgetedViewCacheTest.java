package com.minos.storage.codec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A view above the cache ceiling is held alone and counted until it is removed, cleared or replaced. */
class BudgetedViewCacheTest {

    @Test
    void aViewWithinTheCeilingIsCached() {
        BudgetedViewCache<String, Object> cache = new BudgetedViewCache<>(4, 100L);
        Object view = new Object();

        assertTrue(cache.put("a", view, 60L));

        assertSame(view, cache.get("a"));
        assertEquals(1, cache.size());
        assertEquals(60L, cache.weight());
        assertEquals(60L, cache.cachedWeight());
        assertEquals(0L, cache.heldWeight());
    }

    @Test
    void aViewAboveTheCeilingEmptiesTheCacheAndIsHeldAloneAndCounted() {
        BudgetedViewCache<String, Object> cache = new BudgetedViewCache<>(4, 100L);
        cache.put("a", new Object(), 60L);
        Object heavy = new Object();

        assertFalse(cache.put("b", heavy, 150L));

        assertSame(heavy, cache.get("b"));
        assertNull(cache.get("a"), "the cached view gave way");
        assertEquals(0, cache.size());
        assertEquals(150L, cache.heldWeight());
        assertEquals(150L, cache.weight());
        assertEquals(100L, cache.maximumWeight());
    }

    @Test
    void anotherViewReplacesTheHeldOne() {
        BudgetedViewCache<String, Object> cache = new BudgetedViewCache<>(4, 100L);
        cache.put("b", new Object(), 150L);

        cache.put("c", new Object(), 10L);

        assertNull(cache.get("b"));
        assertEquals(0L, cache.heldWeight());
        assertEquals(10L, cache.weight());
    }

    @Test
    void removeIfReleasesAMatchingHeldViewOnly() {
        BudgetedViewCache<String, Object> cache = new BudgetedViewCache<>(4, 100L);
        cache.put("b", new Object(), 150L);

        cache.removeIf("a"::equals);
        assertEquals(150L, cache.heldWeight());

        cache.removeIf("b"::equals);
        assertEquals(0L, cache.weight());
        assertNull(cache.get("b"));
    }

    @Test
    void clearReleasesEverything() {
        BudgetedViewCache<String, Object> cache = new BudgetedViewCache<>(4, 100L);
        cache.put("a", new Object(), 10L);
        cache.put("b", new Object(), 150L);

        cache.clear();

        assertEquals(0L, cache.weight());
        assertEquals(0L, cache.evictions());
    }

    @Test
    void aNegativeWeightIsRefused() {
        BudgetedViewCache<String, Object> cache = new BudgetedViewCache<>(4, 100L);
        Object view = new Object();

        assertThrows(IllegalArgumentException.class, () -> cache.put("a", view, -1L));
    }
}
