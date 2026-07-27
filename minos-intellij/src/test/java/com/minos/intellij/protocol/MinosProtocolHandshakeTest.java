package com.minos.intellij.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinosProtocolHandshakeTest {

    @Test
    void acceptsProtocolV1WithCapabilities() throws Exception {
        JsonObject handshake = handshake("1");

        JsonObject validated = MinosProtocolHandshake.validate(handshake);

        assertEquals("minos-ide", validated.get("protocol").getAsString());
        assertEquals("1", validated.get("protocolVersion").getAsString());
    }

    @Test
    void rejectsIncompatibleProtocolVersion() {
        MinosProtocolException failure = assertThrows(
                MinosProtocolException.class,
                () -> MinosProtocolHandshake.validate(handshake("2")));

        assertTrue(failure.getMessage().contains("expected 1, received 2"));
    }

    @Test
    void rejectsHandshakeWithoutCapabilities() {
        JsonObject handshake = new JsonObject();
        handshake.addProperty("protocol", "minos-ide");
        handshake.addProperty("protocolVersion", "1");

        assertThrows(MinosProtocolException.class, () -> MinosProtocolHandshake.validate(handshake));
    }

    private static JsonObject handshake(String version) {
        JsonObject handshake = new JsonObject();
        handshake.addProperty("protocol", "minos-ide");
        handshake.addProperty("protocolVersion", version);
        JsonArray capabilities = new JsonArray();
        capabilities.add("project-status");
        handshake.add("capabilities", capabilities);
        return handshake;
    }
}
