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

    /** AUD-ARC-01: the sorted copy keeps the refusals of the engine helper it replaces, and is detached. */
    @Test
    void capabilitiesRefuseANullKeyOrValueAndAreDetachedFromTheSource() {
        Map<String, String> nullKey = new HashMap<>();
        nullKey.put(null, "FULL");
        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("SYMBOLS", null);

        assertEquals("key", assertThrows(NullPointerException.class, () -> dto(nullKey)).getMessage());
        assertEquals("value", assertThrows(NullPointerException.class, () -> dto(nullValue)).getMessage());
        assertEquals("capabilities", assertThrows(NullPointerException.class, () -> dto(null)).getMessage());

        Map<String, String> source = new HashMap<>(Map.of("SYMBOLS", "FULL", "CALLS", "PARTIAL"));
        ProviderPlatformApi.ProviderDto dto = dto(source);
        source.put("REFERENCES", "FULL");
        assertEquals(List.of("CALLS", "SYMBOLS"), new ArrayList<>(dto.capabilities().keySet()));
        assertEquals(dto, dto(Map.of("CALLS", "PARTIAL", "SYMBOLS", "FULL")));
    }

    private static ProviderPlatformApi.ProviderDto dto(Map<String, String> capabilities) {
        return new ProviderPlatformApi.ProviderDto(
                "provider", "1.0", List.of("JAVA"), List.of("MAVEN"), capabilities, 50, List.of(), "READY", List.of());
    }
}
