package com.minos.output;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Q7: what the single JSON encoder accepts (numbers) and how it escapes text. */
class DeterministicJsonEncodingTest {

    private static final ObjectMapper PARSER = new ObjectMapper();

    @Test
    void refusesNotANumberAndInfinitiesInsteadOfWritingInvalidJson() {
        for (Object value : new Object[] {
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertThrows(DeterministicJson.NonFiniteNumberException.class,
                    () -> DeterministicJson.render(value), String.valueOf(value));
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("scores", List.of(1.0, value));
            assertThrows(DeterministicJson.NonFiniteNumberException.class,
                    () -> DeterministicJson.render(nested), "nested " + value);
        }
    }

    @Test
    void nonFiniteRefusalNeverLeaksTheOffendingValueOrPartialOutput() {
        DeterministicJson.NonFiniteNumberException failure = assertThrows(
                DeterministicJson.NonFiniteNumberException.class, () -> DeterministicJson.render(Double.NaN));

        assertTrue(failure.getMessage().contains("NaN or Infinity"));
    }

    @Test
    void finiteNumbersKeepTheirExactExistingText() {
        assertEquals("[1,2,3.5,-0.0,1.0E-5,12345678901234567890.5]",
                DeterministicJson.render(List.of(1, 2L, 3.5d, -0.0d, 1.0E-5d, new BigDecimal("12345678901234567890.5"))));
        assertEquals("0.25", DeterministicJson.number(0.25d));
        assertThrows(DeterministicJson.NonFiniteNumberException.class, () -> DeterministicJson.number(Double.NaN));
        assertThrows(DeterministicJson.NonFiniteNumberException.class,
                () -> DeterministicJson.number(Double.NEGATIVE_INFINITY));
    }

    @Test
    void everyControlCharacterIsEscapedAndRoundTrips() throws Exception {
        for (char control = 0; control < 0x20; control++) {
            String original = "a" + control + "b";
            String json = DeterministicJson.render(original);

            for (int index = 0; index < json.length(); index++) {
                assertTrue(json.charAt(index) >= 0x20, "raw control U+%04X in %s".formatted((int) control, json));
            }
            assertEquals(original, PARSER.readValue(json, String.class), "round trip U+%04X".formatted((int) control));
        }
        assertEquals("\"\\u0000\\u001f\"", DeterministicJson.render("\u0000\u001f"));
        assertEquals("\"\\b\\f\\n\\r\\t\"", DeterministicJson.render("\b\f\n\r\t"));
    }

    @Test
    void quoteAndBackslashAreEscaped() throws Exception {
        String original = "say \"hi\" \\ C:\\path";

        assertEquals("\"say \\\"hi\\\" \\\\ C:\\\\path\"", DeterministicJson.quote(original));
        assertEquals(original, PARSER.readValue(DeterministicJson.quote(original), String.class));
    }

    @Test
    void loneSurrogatesAndLineSeparatorsAreEscapedWhilePairsStayIntact() throws Exception {
        String pair = new String(Character.toChars(0x1F600));

        assertEquals("\"" + pair + "\"", DeterministicJson.quote(pair));
        assertEquals("\"\\ud800\"", DeterministicJson.quote("\ud800"));
        assertEquals("\"\\udc00x\"", DeterministicJson.quote("\udc00x"));
        assertEquals("\"\\ud800a\"", DeterministicJson.quote("\ud800a"));
        assertEquals("\"\\u2028\\u2029\"", DeterministicJson.quote("\u2028\u2029"));
        JsonNode parsed = PARSER.readTree(DeterministicJson.render(List.of("\ud800", "\u2028", pair)));
        assertEquals("\ud800", parsed.get(0).textValue());
        assertEquals(pair, parsed.get(2).textValue());
    }

    @Test
    void quoteIntoABuilderAndQuoteToAStringAgree() {
        StringBuilder builder = new StringBuilder("prefix:");
        DeterministicJson.quote(builder, "a\u0001\"b");

        assertEquals("prefix:" + DeterministicJson.quote("a\u0001\"b"), builder.toString());
    }

    @Test
    void objectKeepsTheInsertionOrderOfItsPairs() {
        assertEquals("{\"z\":1,\"a\":2,\"m\":3}", DeterministicJson.render(DeterministicJson.object("z", 1, "a", 2, "m", 3)));
        assertThrows(IllegalArgumentException.class, () -> DeterministicJson.object("odd"));
    }
}
