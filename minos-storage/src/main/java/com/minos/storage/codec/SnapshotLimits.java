package com.minos.storage.codec;

import com.minos.store.SnapshotReadView;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;

/**
 * The byte ceilings that bound a knowledge snapshot, in one place for both stores (file and PostgreSQL): the
 * ceiling of one persisted snapshot, which also bounds its read, the weight ceiling of the query-view cache, and
 * the factors that turn a persisted size into the estimated weight of its view.
 *
 * <p>The weight of a view is estimated as a multiple of its persisted size. With one instance per distinct string
 * (ADR 0047, option A) a repository-shaped snapshot of the current format retains 1.05 to 1.48 times its file
 * (QueryViewWeightTest, {@code LargeSnapshotMeasurement}); the estimate is the larger of that multiple and a sum
 * over the entity counts, which carries the shapes whose heap exceeds it. Only the current format (V3) has a
 * measured factor; V1 and V2 keep the conservative one, which also bounds a string of two-byte characters.</p>
 *
 * <p>The ceiling of the cache is the configured one; a store applies the smaller of it and the process-wide
 * {@link QueryViewMemoryBudget}, which also bounds the loads in progress (AUD-PERF-15).</p>
 *
 * <p>{@link #production()} holds the values every store uses; a test lowers one of them with {@code with...} to
 * exercise a ceiling without building a snapshot of the production size.</p>
 *
 * @param maxPersistedBytes                      largest encoded snapshot a store publishes or reads
 * @param maxQueryCacheWeightBytes               largest total estimated weight of the cached query views
 * @param currentFormatAmplificationPercent      estimated weight of a V3 view, as a percentage of its persisted size
 * @param unmeasuredFormatAmplificationPercent   same for a format whose amplification was not measured (V1, V2)
 */
public record SnapshotLimits(
        long maxPersistedBytes,
        long maxQueryCacheWeightBytes,
        long currentFormatAmplificationPercent,
        long unmeasuredFormatAmplificationPercent
) {

    /** Production ceiling of the query-view cache, in estimated bytes. */
    public static final long DEFAULT_MAX_QUERY_CACHE_WEIGHT_BYTES = 512L * 1024L * 1024L;
    /** Measured estimate of a V3 view's heap, as a percentage of its persisted size. */
    public static final long CURRENT_FORMAT_AMPLIFICATION_PERCENT = 125L;
    /** Conservative estimate kept for a format whose amplification was not measured. */
    public static final long UNMEASURED_FORMAT_AMPLIFICATION_PERCENT = 200L;
    /**
     * Heap the PostgreSQL driver holds while it fetches a payload, as a percentage of the payload: the text form of a
     * {@code bytea} arrives in hexadecimal (two bytes per byte), then is decoded. Measured at 305 % for a 306 MiB
     * payload before the driver switches to a server-prepared binary fetch (102 % after it), rounded up.
     */
    public static final long POSTGRES_FETCH_AMPLIFICATION_PERCENT = 310L;
    /** Longest wait of a view load for room in the view memory budget before it is refused. */
    public static final Duration VIEW_ADMISSION_WAIT = Duration.ofSeconds(30);

    private static final long PERCENT = 100L;
    private static final long VIEW_BASE_WEIGHT_BYTES = 64L * 1024L;
    private static final long SYMBOL_WEIGHT_BYTES = 1024L;
    private static final long OCCURRENCE_WEIGHT_BYTES = 640L;
    private static final long RELATIONSHIP_WEIGHT_BYTES = 1024L;
    private static final long MEBIBYTE = 1024L * 1024L;
    private static final SnapshotLimits PRODUCTION = new SnapshotLimits(
            SnapshotBinaryCodecSupport.MAX_PERSISTED_SNAPSHOT_BYTES,
            DEFAULT_MAX_QUERY_CACHE_WEIGHT_BYTES,
            CURRENT_FORMAT_AMPLIFICATION_PERCENT,
            UNMEASURED_FORMAT_AMPLIFICATION_PERCENT);

    public SnapshotLimits {
        if (maxPersistedBytes < 1L) {
            throw new IllegalArgumentException("maxPersistedBytes must be greater than zero");
        }
        if (maxQueryCacheWeightBytes < 1L) {
            throw new IllegalArgumentException("maxQueryCacheWeightBytes must be greater than zero");
        }
        if (currentFormatAmplificationPercent < PERCENT || unmeasuredFormatAmplificationPercent < PERCENT) {
            throw new IllegalArgumentException("an amplification must be at least " + PERCENT + " percent");
        }
    }

    /** The limits every store applies unless a test lowers them. */
    public static SnapshotLimits production() {
        return PRODUCTION;
    }

    public SnapshotLimits withMaxPersistedBytes(long bytes) {
        return new SnapshotLimits(bytes, maxQueryCacheWeightBytes,
                currentFormatAmplificationPercent, unmeasuredFormatAmplificationPercent);
    }

    public SnapshotLimits withMaxQueryCacheWeightBytes(long bytes) {
        return new SnapshotLimits(maxPersistedBytes, bytes,
                currentFormatAmplificationPercent, unmeasuredFormatAmplificationPercent);
    }

    /** Amplification, in percent, of a view built from a snapshot of the given format version. */
    public long amplificationPercent(int formatVersion) {
        return formatVersion == SnapshotBinaryCodecSupport.FORMAT_VERSION_V3
                ? currentFormatAmplificationPercent
                : unmeasuredFormatAmplificationPercent;
    }

    /**
     * Estimated heap of a view built from {@code persistedBytes} of snapshot of the given format version,
     * rounded up and saturated at {@link Long#MAX_VALUE}.
     */
    public long amplifiedBytes(int formatVersion, long persistedBytes) {
        long percent = amplificationPercent(formatVersion);
        long bytes = Math.max(0L, persistedBytes);
        if (bytes > Long.MAX_VALUE / percent) {
            return Long.MAX_VALUE;
        }
        return Math.ceilDiv(bytes * percent, PERCENT);
    }

    /** Heap the PostgreSQL driver holds while it fetches {@code payloadBytes}, rounded up and saturated. */
    public long fetchTransientBytes(long payloadBytes) {
        long bytes = Math.max(0L, payloadBytes);
        if (bytes > Long.MAX_VALUE / POSTGRES_FETCH_AMPLIFICATION_PERCENT) {
            return Long.MAX_VALUE;
        }
        return Math.ceilDiv(bytes * POSTGRES_FETCH_AMPLIFICATION_PERCENT, PERCENT);
    }

    /**
     * Refuses a stored payload size outside {@code [1, maxPersistedBytes]} with the message of the persisted ceiling
     * on read: sizes only, never a path.
     */
    public void requirePersistedSize(long payloadBytes) throws IOException {
        if (payloadBytes < 1L || payloadBytes > maxPersistedBytes) {
            throw new IOException("snapshot payload exceeds persisted byte limit: " + payloadBytes
                    + "/" + maxPersistedBytes);
        }
    }

    /**
     * Estimated weight of the view of the largest persistable snapshot of the current format, from its persisted size
     * alone: the term {@link #requireCacheCoversPersistable()} compares to the cache ceiling.
     */
    public long largestPersistableViewWeightBytes() {
        return amplifiedBytes(SnapshotBinaryCodecSupport.FORMAT_VERSION_V3, maxPersistedBytes);
    }

    /**
     * Estimated weight of the view of the largest persistable snapshot of the current format holding the given
     * numbers of symbols, occurrences and relationships, derived index of incoming edges included: the weight both
     * stores estimate for a snapshot of that size and of those counts.
     */
    public long largestPersistableViewWeightBytes(long symbols, long occurrences, long relationships) {
        return viewWeightBytes(SnapshotBinaryCodecSupport.FORMAT_VERSION_V3, maxPersistedBytes,
                symbols, occurrences, relationships);
    }

    /**
     * Estimated weight of a view: the larger of a sum over the entity counts and of the amplified persisted size,
     * plus the derived index of incoming edges, which lives as long as the view (AUD-PERF-11). Saturated at
     * {@link Long#MAX_VALUE}.
     */
    public long viewWeightBytes(int formatVersion, long persistedBytes, long symbols, long occurrences,
                                long relationships) {
        long countWeight = VIEW_BASE_WEIGHT_BYTES;
        countWeight = saturatingAdd(countWeight, saturatingMultiply(symbols, SYMBOL_WEIGHT_BYTES));
        countWeight = saturatingAdd(countWeight, saturatingMultiply(occurrences, OCCURRENCE_WEIGHT_BYTES));
        countWeight = saturatingAdd(countWeight, saturatingMultiply(relationships, RELATIONSHIP_WEIGHT_BYTES));
        long persistedWeight = amplifiedBytes(formatVersion, persistedBytes);
        return saturatingAdd(Math.max(countWeight, persistedWeight),
                SnapshotReadView.incomingRelationshipsWeightBytes(Math.max(0L, relationships)));
    }

    /**
     * Refuses limits under which a snapshot of the current format that a store accepts could be refused by the
     * cache for its estimated weight alone: the cache ceiling must be at least the persisted ceiling times the
     * amplification of the current format. The message names the three values.
     *
     * <p>The invariant covers the amplified size only, neither the derived index of incoming edges nor the sum over
     * the entity counts: the number of relationships of a persistable snapshot has no bound, so no constant ceiling
     * could cover it. A view above the cache ceiling that the budget admits is held alone by its store and stays
     * counted (AUD-PERF-06); {@link #largestPersistableViewWeightBytes(long, long, long)} gives its full weight.</p>
     */
    public void requireCacheCoversPersistable() {
        long required = largestPersistableViewWeightBytes();
        if (maxQueryCacheWeightBytes < required) {
            throw new IllegalStateException("query view cache ceiling " + maxQueryCacheWeightBytes
                    + " bytes is below persisted snapshot ceiling " + maxPersistedBytes
                    + " bytes times current format amplification " + currentFormatAmplificationPercent
                    + " percent (" + required + " bytes)");
        }
    }

    private static long saturatingAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    private static long saturatingMultiply(long count, long weight) {
        long value = Math.max(0L, count);
        return value > Long.MAX_VALUE / weight ? Long.MAX_VALUE : value * weight;
    }

    /** A byte count in mebibytes: whole when exact, with two decimals otherwise. */
    static String describeMebibytes(long bytes) {
        if (bytes % MEBIBYTE == 0L) {
            return bytes / MEBIBYTE + " MiB";
        }
        return String.format(Locale.ROOT, "%.2f MiB", bytes / (double) MEBIBYTE);
    }
}
