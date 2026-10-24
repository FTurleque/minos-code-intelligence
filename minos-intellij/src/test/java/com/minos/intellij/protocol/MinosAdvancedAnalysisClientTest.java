package com.minos.intellij.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinosAdvancedAnalysisClientTest {

    @Test
    void acceptsAdvertisedCapability() {
        JsonObject handshake = handshake(MinosAdvancedAnalysisClient.PROGRAM_GRAPH, MinosAdvancedAnalysisClient.HYBRID_SEARCH);

        assertDoesNotThrow(() -> MinosAdvancedAnalysisClient.requireCapability(handshake, MinosAdvancedAnalysisClient.PROGRAM_GRAPH));
    }

    @Test
    void rejectsCapabilityMissingFromOlderRuntime() {
        JsonObject handshake = handshake("project-status", "architecture");

        MinosProtocolException failure = assertThrows(
                MinosProtocolException.class,
                () -> MinosAdvancedAnalysisClient.requireCapability(handshake, MinosAdvancedAnalysisClient.SEMANTIC_SEARCH));

        assertTrue(failure.getMessage().contains("semantic-search"));
        assertTrue(failure.getMessage().contains("update MINOS"));
    }

    @Test
    void rejectsMissingCapabilityArray() {
        MinosProtocolException failure = assertThrows(
                MinosProtocolException.class,
                () -> MinosAdvancedAnalysisClient.requireCapability(new JsonObject(), MinosAdvancedAnalysisClient.IMPACT_V2));

        assertTrue(failure.getMessage().contains("impact-v2"));
    }

    private static JsonObject handshake(String... values) {
        JsonObject handshake = new JsonObject();
        JsonArray capabilities = new JsonArray();
        for (String value : values) capabilities.add(value);
        handshake.add("capabilities", capabilities);
        return handshake;
    }
}
