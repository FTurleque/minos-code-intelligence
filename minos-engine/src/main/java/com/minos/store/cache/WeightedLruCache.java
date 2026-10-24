package com.minos.store.cache;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Least-recently-used cache bounded both by its number of entries and by the total weight of its entries.
 *
 * <p>The caller states the weight of each entry when it inserts it, usually an estimate of the heap the value
 * retains, computed before the call. The cache never holds more than {@link #maximumEntries()} entries nor more
 * than {@link #maximumWeight()} in total: after each insertion it evicts the least recently read entries until both
 * bounds hold. An entry heavier than the maximum weight on its own is not kept and evicts nothing. The weight of an
 * entry is subtracted whenever it leaves the cache, whether it is removed, replaced, invalidated or evicted.</p>
 *
 * <p>Every operation, reads included (a read moves the entry to the most recent end), is serialised by a private
 * lock. Under that lock the cache runs no caller code other than the {@code equals} and {@code hashCode} of its keys
 * and the predicate of {@link #removeIf(Predicate)}; none of them may call back into the cache. An owner that needs a
 * compound step (read, then insert if still absent) holds its own lock around the calls: the lock order is always
 * owner first, cache second.</p>
 *
 * @param <K> key type, with value-based {@code equals} and {@code hashCode}
 * @param <V> value type
 */
public final class WeightedLruCache<K, V> {

    /** Largest accepted maximum weight: the sum of the kept weights and of one new entry cannot overflow. */
    public static final long MAXIMUM_WEIGHT_LIMIT = Long.MAX_VALUE / 2L;

    private final Object lock = new Object();
    private final int maximumEntries;
    private final long maximumWeight;
    private final LinkedHashMap<K, Weighted<V>> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long weight;
    private long evictions;

    /**
     * @param maximumEntries largest number of entries, at least 1 ({@link Integer#MAX_VALUE} bounds by weight alone)
     * @param maximumWeight  largest total weight, between 1 and {@link #MAXIMUM_WEIGHT_LIMIT}
     * @throws IllegalArgumentException when a bound is out of range
     */
    public WeightedLruCache(int maximumEntries, long maximumWeight) {
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("maximumEntries must be at least 1: " + maximumEntries);
        }
        if (maximumWeight < 1L || maximumWeight > MAXIMUM_WEIGHT_LIMIT) {
            throw new IllegalArgumentException(
                    "maximumWeight must be between 1 and " + MAXIMUM_WEIGHT_LIMIT + ": " + maximumWeight);
        }
        this.maximumEntries = maximumEntries;
        this.maximumWeight = maximumWeight;
    }

    /** The value of {@code key}, now the most recently read entry, or {@code null} when the cache does not hold it. */
    public V get(K key) {
        Objects.requireNonNull(key, "key");
        synchronized (lock) {
            Weighted<V> entry = entries.get(key);
            return entry == null ? null : entry.value();
        }
    }

    /**
     * Puts {@code value} under {@code key} with the given weight. The previous value of the key, if any, leaves the
     * cache first. A value heavier than {@link #maximumWeight()} is not kept and evicts nothing; otherwise the least
     * recently read entries are evicted until both bounds hold.
     *
     * @return whether the value is now held by the cache
     * @throws IllegalArgumentException when {@code entryWeight} is negative; the cache is then left unchanged
     */
    public boolean put(K key, V value, long entryWeight) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        if (entryWeight < 0L) throw new IllegalArgumentException("entry weight must not be negative: " + entryWeight);
        synchronized (lock) {
            Weighted<V> previous = entries.remove(key);
            if (previous != null) weight -= previous.weight();
            if (entryWeight > maximumWeight) return false;
            entries.put(key, new Weighted<>(value, entryWeight));
            weight += entryWeight;
            evictLeastRecentlyRead();
            return true;
        }
    }

    /** Removes the value of {@code key}; returns it, or {@code null} when the cache did not hold it. */
    public V remove(K key) {
        Objects.requireNonNull(key, "key");
        synchronized (lock) {
            Weighted<V> removed = entries.remove(key);
            if (removed == null) return null;
            weight -= removed.weight();
            return removed.value();
        }
    }

    /**
     * Removes every entry whose key matches {@code keyFilter}; an invalidation, not counted as an eviction.
     *
     * @return the number of entries removed
     */
    public int removeIf(Predicate<? super K> keyFilter) {
        Objects.requireNonNull(keyFilter, "keyFilter");
        synchronized (lock) {
            int removed = 0;
            Iterator<Map.Entry<K, Weighted<V>>> iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<K, Weighted<V>> entry = iterator.next();
                if (keyFilter.test(entry.getKey())) {
                    weight -= entry.getValue().weight();
                    iterator.remove();
                    removed++;
                }
            }
            return removed;
        }
    }

    /** Number of entries held. */
    public int size() {
        synchronized (lock) {
            return entries.size();
        }
    }

    /** Total weight of the entries held. */
    public long weight() {
        synchronized (lock) {
            return weight;
        }
    }

    /** Number of entries evicted to restore a bound since the cache was created; removals are not counted. */
    public long evictions() {
        synchronized (lock) {
            return evictions;
        }
    }

    public int maximumEntries() {
        return maximumEntries;
    }

    public long maximumWeight() {
        return maximumWeight;
    }

    /**
     * The entry just inserted is the most recent one and weighs at most the maximum, so the loop stops before it:
     * once every older entry is gone, one entry of admissible weight remains, within both bounds.
     */
    private void evictLeastRecentlyRead() {
        Iterator<Map.Entry<K, Weighted<V>>> iterator = entries.entrySet().iterator();
        while (entries.size() > maximumEntries || weight > maximumWeight) {
            weight -= iterator.next().getValue().weight();
            iterator.remove();
            evictions++;
        }
    }

    private record Weighted<V>(V value, long weight) { }
}
