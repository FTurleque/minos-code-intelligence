package com.minos.store.cache;

/**
 * The share of the maximum heap each application cache may retain (AUD-PERF-04). A cache applies the smaller of its
 * configured weight ceiling and its share of {@link Runtime#maxMemory()}: under a large heap nothing changes, under
 * the default heap of the Docker query plane the caches together stay within what the view budget and the decoding of
 * a view leave free.
 *
 * <p>The shares were fixed on a measurement of the retained heap of each cache (design of
 * {@code borner-les-caches-applicatifs-par-le-tas}, ADR DATA-0085): the view budget keeps 50 percent of the heap, the
 * decoding of a view that fills it uses up to 34 percent more, 3 percent is left to the work of the requests, and
 * {@value #CACHES_PERCENT} percent goes to the caches below. Every estimate of these caches was measured above the
 * heap it retains, so a ceiling taken from a share bounds what they retain.</p>
 */
public enum HeapShare {
    /** Hybrid lexical corpus of a project. */
    HYBRID_CORPUS(6),
    /** Program graphs with their impact adjacency. */
    PROGRAM_GRAPH(2),
    /** Fingerprints of the Java advanced provider. */
    JAVA_FINGERPRINTS(1),
    /** Decoded semantic projection. */
    SEMANTIC_PROJECTION(1),
    /** Embedded query vectors. */
    QUERY_VECTORS(1),
    /** Decoded local vector index. */
    LOCAL_VECTOR_INDEX(2);

    /** Sum of the shares, in percent of the maximum heap. */
    public static final int CACHES_PERCENT = 13;
    /** Heap a view uses while it is decoded beyond its estimated weight, in percent of the heap (peak of 0.84). */
    public static final int VIEW_DECODING_MARGIN_PERCENT = 34;
    /** Heap left to the work of the requests, in percent. */
    public static final int REQUEST_WORK_PERCENT = 3;

    private static final long HUNDRED = 100L;

    private final int percent;

    HeapShare(int percent) {
        this.percent = percent;
    }

    public int percent() {
        return percent;
    }

    /** The ceiling of this cache in this process: the smaller of {@code configuredCeiling} and its heap share. */
    public long ceiling(long configuredCeiling) {
        return ceiling(configuredCeiling, Runtime.getRuntime().maxMemory());
    }

    /** The ceiling of this cache under a maximum heap of {@code maximumHeapBytes}. */
    public long ceiling(long configuredCeiling, long maximumHeapBytes) {
        return ceiling(configuredCeiling, percent, maximumHeapBytes);
    }

    /**
     * The smaller of {@code configuredCeiling} and {@code percent} of {@code maximumHeapBytes}, at least 1.
     *
     * @throws IllegalArgumentException when the share is not from 1 to 100 percent, or a size is not positive
     */
    public static long ceiling(long configuredCeiling, int percent, long maximumHeapBytes) {
        if (percent < 1 || percent > 100) {
            throw new IllegalArgumentException("heap share must be from 1 to 100 percent: " + percent);
        }
        if (configuredCeiling < 1L) throw new IllegalArgumentException("configuredCeiling must be greater than zero");
        if (maximumHeapBytes < 1L) throw new IllegalArgumentException("maximumHeapBytes must be greater than zero");
        long share = (maximumHeapBytes / HUNDRED) * percent + (maximumHeapBytes % HUNDRED) * percent / HUNDRED;
        return Math.clamp(share, 1L, configuredCeiling);
    }
}
