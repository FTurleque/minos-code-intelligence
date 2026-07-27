package com.minos.intellij.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class MinosProtocolHandshake {

    private MinosProtocolHandshake() {
    }

    public static JsonObject validate(JsonObject result) throws MinosProtocolException {
        if (result == null) {
            throw new MinosProtocolException("MINOS IDE handshake returned no object");
        }
        String protocol = requiredString(result, "protocol");
        String version = requiredString(result, "protocolVersion");
        if (!MinosCliClient.PROTOCOL_ID.equals(protocol)) {
            throw new MinosProtocolException("Unexpected MINOS IDE protocol: " + protocol);
        }
        if (!MinosCliClient.PROTOCOL_VERSION.equals(version)) {
            throw new MinosProtocolException(
                    "Incompatible MINOS IDE protocol: expected " + MinosCliClient.PROTOCOL_VERSION
                            + ", received " + version);
        }
        JsonElement capabilitiesElement = result.get("capabilities");
        JsonArray capabilities = capabilitiesElement != null && capabilitiesElement.isJsonArray()
                ? capabilitiesElement.getAsJsonArray()
                : new JsonArray();
        if (capabilities.size() == 0) {
            throw new MinosProtocolException("MINOS IDE handshake returned no capabilities");
        }
        return result;
    }

    private static String requiredString(JsonObject object, String name) throws MinosProtocolException {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull() || element.getAsString().isBlank()) {
            throw new MinosProtocolException("MINOS IDE handshake is missing `" + name + "`");
        }
        return element.getAsString();
    }
}
