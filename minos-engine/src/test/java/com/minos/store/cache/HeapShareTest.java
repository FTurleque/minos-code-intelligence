package com.minos.store.cache;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A cache keeps the smaller of its configured ceiling and its share of the heap (AUD-PERF-04). */
class HeapShareTest {

    private static final long MEBIBYTE = 1024L * 1024L;

    @Test
    void underASmallHeapTheCeilingIsTheShare() {
        long heap = 1_128_267_776L;

        assertEquals(heap / 100L * 6L + heap % 100L * 6L / 100L,
                HeapShare.HYBRID_CORPUS.ceiling(256L * MEBIBYTE, heap));
        assertTrue(HeapShare.HYBRID_CORPUS.ceiling(256L * MEBIBYTE, heap) < 256L * MEBIBYTE);
    }

    @Test
    void underALargeHeapTheCeilingIsTheConfiguredOne() {
        assertEquals(8L * MEBIBYTE, HeapShare.QUERY_VECTORS.ceiling(8L * MEBIBYTE, 16L * 1024L * MEBIBYTE));
    }

    @Test
    void aShareOutsideOneToHundredPercentIsRefusedByName() {
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> HeapShare.ceiling(10L, 0, 100L));
        assertTrue(refused.getMessage().contains("heap share"), refused.getMessage());
        assertThrows(IllegalArgumentException.class, () -> HeapShare.ceiling(10L, -1, 100L));
        assertThrows(IllegalArgumentException.class, () -> HeapShare.ceiling(10L, 101, 100L));
        assertThrows(IllegalArgumentException.class, () -> HeapShare.ceiling(0L, 1, 100L));
        assertThrows(IllegalArgumentException.class, () -> HeapShare.ceiling(10L, 1, 0L));
    }

    @Test
    void theCeilingIsAtLeastOneByte() {
        assertEquals(1L, HeapShare.ceiling(10L, 1, 50L));
    }

    @Test
    void theSharesAddUpToTheDeclaredSumAndLeaveRoomForTheViewBudget() {
        int sum = Arrays.stream(HeapShare.values()).mapToInt(HeapShare::percent).sum();

        assertEquals(HeapShare.CACHES_PERCENT, sum);
        assertTrue(50 + HeapShare.VIEW_DECODING_MARGIN_PERCENT + HeapShare.CACHES_PERCENT
                + HeapShare.REQUEST_WORK_PERCENT <= 100);
        assertTrue(HeapShare.PROGRAM_GRAPH.ceiling(1L) == 1L);
    }
}
