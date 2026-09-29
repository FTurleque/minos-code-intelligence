package com.minos.application;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Q6: the capabilities of a provider view keep a fixed key order on every JVM. */
class ProviderViewOrderTest {

    @Test
    void capabilitiesAreKeptSortedWhateverTheirInsertionOrder() {
        Map<String, String> unordered = new HashMap<>();
        for (int index = 14; index >= 1; index--) unordered.put("CAPABILITY_" + index, "FULL");

        ProviderPlatformService.ProviderView view = view(unordered);

        List<String> expected = new ArrayList<>(unordered.keySet());
        Collections.sort(expected);
        assertEquals(expected, new ArrayList<>(view.capabilities().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> view.capabilities().put("X", "Y"));
        assertThrows(NullPointerException.class, () -> view(null));
    }

    private static ProviderPlatformService.ProviderView view(Map<String, String> capabilities) {
        return new ProviderPlatformService.ProviderView(
                "provider", "1.0", "QUALIFIED", List.of("JAVA"), List.of("MAVEN"), capabilities, 50, List.of(), true,
                List.of(), List.of(), "ready", "install", "identity", "provenance", "READY", List.of());
    }
}
