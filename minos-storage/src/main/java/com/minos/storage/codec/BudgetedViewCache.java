package com.minos.storage.codec;

import com.minos.store.cache.WeightedLruCache;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * The views a snapshot store keeps for the {@link QueryViewMemoryBudget}: a weighted LRU cache, plus one slot for a
 * view the budget admitted but that is heavier than the cache ceiling (AUD-PERF-06).
 *
 * <p>Such a view is held alone: holding it empties the cache, and putting any other view releases it. Its weight
 * is part of {@link #weight()}, so the budget counts it as resident until it leaves by {@link #clear()}, by
 * {@link #removeIf(Predicate)} or by its replacement. A store therefore never returns an admitted view whose weight is
 * not counted.</p>
 *
 * <p>Every operation is serialised by a private lock and runs no caller code other than the {@code equals} and
 * {@code hashCode} of the keys and the predicate of {@link #removeIf(Predicate)}. An owner that needs a compound step
 * holds its own lock around the calls: owner first, this cache second.</p>
 *
 * @param <K> key type, with value-based {@code equals} and {@code hashCode}
 * @param <V> value type
 */
public final class BudgetedViewCache<K, V> {

    private final Object lock = new Object();
    private final WeightedLruCache<K, V> cache;
    private K heldKey;
    private V heldValue;
    private long heldWeight;

    /**
     * @param maximumEntries largest number of cached entries, at least 1
     * @param maximumWeight  cache ceiling; a heavier view is held alone instead of being cached
     */
    public BudgetedViewCache(int maximumEntries, long maximumWeight) {
        this.cache = new WeightedLruCache<>(maximumEntries, maximumWeight);
    }

    /** The view of {@code key}, cached or held, or {@code null}. */
    public V get(K key) {
        Objects.requireNonNull(key, "key");
        synchronized (lock) {
            if (heldKey != null && heldKey.equals(key)) return heldValue;
            return cache.get(key);
        }
    }

    /**
     * Keeps {@code value} under {@code key}. A held view is released first. A value within the cache ceiling is
     * cached; a heavier one empties the cache and is held alone.
     *
     * @return whether the value went to the cache ({@code false}: it is held alone)
     */
    public boolean put(K key, V value, long weight) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        if (weight < 0L) throw new IllegalArgumentException("weight must not be negative: " + weight);
        synchronized (lock) {
            release();
            if (weight <= cache.maximumWeight()) {
                return cache.put(key, value, weight);
            }
            cache.removeIf(any -> true);
            heldKey = key;
            heldValue = value;
            heldWeight = weight;
            return false;
        }
    }

    /** Removes every cached or held view whose key matches {@code keyFilter}. */
    public void removeIf(Predicate<? super K> keyFilter) {
        Objects.requireNonNull(keyFilter, "keyFilter");
        synchronized (lock) {
            cache.removeIf(keyFilter);
            if (heldKey != null && keyFilter.test(heldKey)) release();
        }
    }

    /** Removes every view, cached or held. */
    public void clear() {
        removeIf(any -> true);
    }

    /** Weight of the cached views plus that of the held view: what the budget counts as resident. */
    public long weight() {
        synchronized (lock) {
            return cache.weight() + heldWeight;
        }
    }

    /** Weight of the view held alone above the cache ceiling, 0 when none is held. */
    public long heldWeight() {
        synchronized (lock) {
            return heldWeight;
        }
    }

    /** Weight of the cached views alone. */
    public long cachedWeight() {
        return cache.weight();
    }

    /** Number of cached views; a held view is not counted. */
    public int size() {
        return cache.size();
    }

    public long evictions() {
        return cache.evictions();
    }

    public long maximumWeight() {
        return cache.maximumWeight();
    }

    private void release() {
        heldKey = null;
        heldValue = null;
        heldWeight = 0L;
    }
}
