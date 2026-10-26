package com.minos.application.export;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeExportBoundedSelectionTest {

    /** A selection bounded by count only: every value weighs one, against an unlimited weight. */
    private static KnowledgeExportUseCase.BoundedById<String> countOnly(int limit) {
        return new KnowledgeExportUseCase.BoundedById<>(limit, Long.MAX_VALUE, value -> 1L);
    }

    @Test
    void retainsDeterministicLowestIdsWithoutTruncationBelowLimit() {
        KnowledgeExportUseCase.BoundedById<String> selected = countOnly(3);
        selected.offer("b", "B");
        selected.offer("a", "A");

        assertEquals(List.of("A", "B"), List.copyOf(selected.values()));
        assertFalse(selected.truncated());
    }

    @Test
    void exactLimitIsNotReportedAsTruncated() {
        KnowledgeExportUseCase.BoundedById<String> selected = countOnly(2);
        selected.offer("b", "B");
        selected.offer("a", "A");

        assertEquals(List.of("A", "B"), List.copyOf(selected.values()));
        assertFalse(selected.truncated());
    }

    @Test
    void aboveLimitKeepsLowestIdsAndReportsTruncationRegardlessOfInputOrder() {
        KnowledgeExportUseCase.BoundedById<String> selected = countOnly(2);
        selected.offer("z", "Z");
        selected.offer("a", "A");
        selected.offer("m", "M");
        selected.offer("b", "B");

        assertEquals(List.of("A", "B"), List.copyOf(selected.values()));
        assertEquals(List.of("a", "b"), List.copyOf(selected.orderedMap().keySet()));
        assertTrue(selected.truncated());
    }

    @Test
    void aboveWeightBudgetDropsHighestIdsAndReportsTruncation() {
        KnowledgeExportUseCase.BoundedById<String> selected =
                new KnowledgeExportUseCase.BoundedById<>(10, 10L, value -> 4L);
        selected.offer("c", "C");
        selected.offer("a", "A");
        selected.offer("b", "B");

        assertEquals(List.of("A", "B"), List.copyOf(selected.values()));
        assertTrue(selected.truncated());
    }

    @Test
    void anOfferForARetainedIdReplacesItsValueAndWeight() {
        KnowledgeExportUseCase.BoundedById<String> selected =
                new KnowledgeExportUseCase.BoundedById<>(10, 10L, String::length);
        selected.offer("a", "AAAAAAAA");
        selected.offer("a", "A");
        selected.offer("b", "BBBBBBBB");

        assertEquals(List.of("A", "BBBBBBBB"), List.copyOf(selected.values()));
        assertFalse(selected.truncated());
    }

    @Test
    void aWeightBelowOneCountsAsOne() {
        KnowledgeExportUseCase.BoundedById<String> selected =
                new KnowledgeExportUseCase.BoundedById<>(10, 2L, value -> 0L);
        selected.offer("a", "A");
        selected.offer("b", "B");
        selected.offer("c", "C");

        assertEquals(List.of("A", "B"), List.copyOf(selected.values()));
        assertTrue(selected.truncated());
    }

    @Test
    void invalidBoundsAndArgumentsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> countOnly(0));
        assertThrows(IllegalArgumentException.class,
                () -> new KnowledgeExportUseCase.BoundedById<String>(1, 0L, value -> 1L));
        assertThrows(NullPointerException.class, () -> new KnowledgeExportUseCase.BoundedById<String>(1, 1L, null));
        KnowledgeExportUseCase.BoundedById<String> selected = countOnly(1);
        assertThrows(NullPointerException.class, () -> selected.offer(null, "A"));
        assertThrows(NullPointerException.class, () -> selected.offer("a", null));
    }
}
