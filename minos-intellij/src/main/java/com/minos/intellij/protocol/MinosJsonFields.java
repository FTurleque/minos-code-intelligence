package com.minos.intellij.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** The fields of a JSON object printed by the MINOS CLI, read the same way by every action (AUD-TST-05). */
public final class MinosJsonFields {

    private MinosJsonFields() {
    }

    /** The string value of {@code name}, or null when the field is absent or JSON null. */
    public static String nullable(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    /** The string value of {@code name}; a missing object, field or blank value is refused. */
    public static String required(JsonObject object, String name) {
        String value = object == null ? null : nullable(object, name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("MINOS symbol is missing `" + name + "`");
        }
        return value;
    }
}
