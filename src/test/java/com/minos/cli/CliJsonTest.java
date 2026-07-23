package com.minos.cli;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CliJsonTest {

    @Test
    void rendersStableJsonWithEscapingNullAndOrderedFields() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("text", "a\"b\\c\n");
        value.put("missing", null);
        value.put("items", List.of("x", 2, true));

        assertEquals(
                "{\"text\":\"a\\\"b\\\\c\\n\",\"missing\":null,\"items\":[\"x\",2,true]}",
                CliJson.render(value)
        );
    }
}
