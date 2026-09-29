package com.minos.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Q6: the capabilities of a provider DTO keep a fixed key order on every JVM. */
class ProviderDtoOrderTest {

    @Test
    void capabilitiesAreKeptSortedWhateverTheirInsertionOrder() {
        Map<String, String> unordered = new HashMap<>();
        for (int index = 14; index >= 1; index--) unordered.put("CAPABILITY_" + index, "FULL");

        ProviderPlatformApi.ProviderDto dto = dto(unordered);

        List<String> expected = new ArrayList<>(unordered.keySet());
        Collections.sort(expected);
        assertEquals(expected, new ArrayList<>(dto.capabilities().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> dto.capabilities().put("X", "Y"));
        assertThrows(NullPointerException.class, () -> dto(null));
    }

    private static ProviderPlatformApi.ProviderDto dto(Map<String, String> capabilities) {
        return new ProviderPlatformApi.ProviderDto(
                "provider", "1.0", List.of("JAVA"), List.of("MAVEN"), capabilities, 50, List.of(), "READY", List.of());
    }
}
