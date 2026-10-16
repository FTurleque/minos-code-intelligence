package com.minos.intellij.protocol;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MinosJsonFieldsTest {

    @Test
    void aMissingOrNullFieldIsNull() {
        JsonObject object = new JsonObject();
        object.add("empty", JsonNull.INSTANCE);
        object.addProperty("name", "Main");

        assertNull(MinosJsonFields.nullable(object, "absent"));
        assertNull(MinosJsonFields.nullable(object, "empty"));
        assertEquals("Main", MinosJsonFields.nullable(object, "name"));
    }

    @Test
    void aRequiredFieldMustBePresentAndNotBlank() {
        JsonObject object = new JsonObject();
        object.addProperty("id", "sym-1");
        object.addProperty("blank", "  ");

        assertEquals("sym-1", MinosJsonFields.required(object, "id"));
        assertEquals("MINOS symbol is missing `blank`",
                assertThrows(IllegalArgumentException.class, () -> MinosJsonFields.required(object, "blank")).getMessage());
        assertEquals("MINOS symbol is missing `absent`",
                assertThrows(IllegalArgumentException.class, () -> MinosJsonFields.required(object, "absent")).getMessage());
        assertEquals("MINOS symbol is missing `id`",
                assertThrows(IllegalArgumentException.class, () -> MinosJsonFields.required(null, "id")).getMessage());
    }
}
