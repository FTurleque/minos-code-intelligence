package com.minos.integration.nexus;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NexusExportBoundedSelectionTest {

    @Test
    void retainsDeterministicLowestIdsWithoutTruncationBelowLimit() {
        NexusExportService.BoundedById<String> selected = new NexusExportService.BoundedById<>(3);
        selected.offer("b", "B");
        selected.offer("a", "A");

        assertEquals(List.of("A", "B"), List.copyOf(selected.values()));
        assertFalse(selected.truncated());
    }

    @Test
    void exactLimitIsNotReportedAsTruncated() {
        NexusExportService.BoundedById<String> selected = new NexusExportService.BoundedById<>(2);
        selected.offer("b", "B");
        selected.offer("a", "A");

        assertEquals(List.of("A", "B"), List.copyOf(selected.values()));
        assertFalse(selected.truncated());
    }

    @Test
    void aboveLimitKeepsLowestIdsAndReportsTruncationRegardlessOfInputOrder() {
        NexusExportService.BoundedById<String> selected = new NexusExportService.BoundedById<>(2);
        selected.offer("z", "Z");
        selected.offer("a", "A");
        selected.offer("m", "M");
        selected.offer("b", "B");

        assertEquals(List.of("A", "B"), List.copyOf(selected.values()));
        assertEquals(List.of("a", "b"), List.copyOf(selected.orderedMap().keySet()));
        assertTrue(selected.truncated());
    }
}
