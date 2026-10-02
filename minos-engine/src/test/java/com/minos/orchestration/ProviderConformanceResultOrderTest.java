package com.minos.orchestration;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Q6: the capabilities of a provider are rendered as a JSON object, so their order must not depend on
 * the JVM. {@code Map.copyOf} iterates in an order drawn at random when the JVM starts.
 */
class ProviderConformanceResultOrderTest {

    @Test
    void capabilitiesAreKeptSortedByCapabilityNameWhateverTheirInsertionOrder() {
        Map<String, String> unordered = new HashMap<>();
        List<IndexerCapability> reversed = new ArrayList<>(List.of(IndexerCapability.values()));
        Collections.reverse(reversed);
        reversed.forEach(capability -> unordered.put(capability.name(), CapabilitySupportLevel.FULL.name()));

        ProviderConformanceKit.ConformanceResult result = result(unordered, new EnumMap<>(CapabilitySupportLevel.class));

        List<String> expected = new ArrayList<>(unordered.keySet());
        Collections.sort(expected);
        assertEquals(expected, new ArrayList<>(result.capabilities().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> result.capabilities().put("X", "Y"));
    }

    @Test
    void countsAreKeptInSupportLevelOrder() {
        Map<CapabilitySupportLevel, Integer> counts = new HashMap<>();
        List<CapabilitySupportLevel> reversed = new ArrayList<>(List.of(CapabilitySupportLevel.values()));
        Collections.reverse(reversed);
        reversed.forEach(level -> counts.put(level, level.ordinal()));

        ProviderConformanceKit.ConformanceResult result = result(Map.of("SYMBOLS", "FULL"), counts);

        assertEquals(List.of(CapabilitySupportLevel.values()), new ArrayList<>(result.counts().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> result.counts().put(CapabilitySupportLevel.FULL, 9));
    }

    @Test
    void sortedCopyRefusesNullKeysAndValuesLikeMapCopyOfDid() {
        Map<String, String> withNullValue = new HashMap<>();
        withNullValue.put("A", null);
        Map<String, String> withNullKey = new HashMap<>();
        withNullKey.put(null, "A");

        assertThrows(NullPointerException.class, () -> ProviderConformanceKit.sortedCopy(withNullValue));
        assertThrows(NullPointerException.class, () -> ProviderConformanceKit.sortedCopy(withNullKey));
        assertThrows(NullPointerException.class, () -> ProviderConformanceKit.sortedCopy(null));
    }

    private static ProviderConformanceKit.ConformanceResult result(
            Map<String, String> capabilities, Map<CapabilitySupportLevel, Integer> counts) {
        return new ProviderConformanceKit.ConformanceResult(
                "provider", "1.0", "QUALIFIED", List.of("JAVA"), List.of("MAVEN"), capabilities, counts,
                50, List.of(), true, List.of(), List.of(), "ready", "install", "identity", "provenance");
    }
}
