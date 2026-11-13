package com.minos.explorer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Strict reader of the only JSON the surface accepts: one flat object whose values are strings, numbers,
 * booleans or {@code null}. No nesting, no duplicate key, nothing after the object. Anything else is refused
 * with {@link IllegalArgumentException}, whose message never repeats the input.
 */
final class JsonObjectReader {

    private static final String INVALID = "the request body must be one flat JSON object";
    private static final int MAX_MEMBERS = 16;

    private final String text;
    private int position;

    private JsonObjectReader(String text) {
        this.text = text;
    }

    /** The members of the object: a {@link String}, a {@link Double}, a {@link Boolean} or {@code null} each. */
    static Map<String, Object> read(String text) {
        if (text == null) throw new IllegalArgumentException(INVALID);
        JsonObjectReader reader = new JsonObjectReader(text);
        Map<String, Object> members = reader.object();
        reader.skipWhitespace();
        if (reader.position != text.length()) throw new IllegalArgumentException(INVALID);
        return members;
    }

    private Map<String, Object> object() {
        Map<String, Object> members = new LinkedHashMap<>();
        skipWhitespace();
        expect('{');
        skipWhitespace();
        if (peek() == '}') {
            position++;
            return members;
        }
        while (true) {
            skipWhitespace();
            String key = string();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            if (members.size() == MAX_MEMBERS || members.containsKey(key)) throw new IllegalArgumentException(INVALID);
            members.put(key, value());
            skipWhitespace();
            char next = next();
            if (next == '}') return members;
            if (next != ',') throw new IllegalArgumentException(INVALID);
        }
    }

    private Object value() {
        char first = peek();
        if (first == '"') return string();
        if (first == 't') return literal("true", Boolean.TRUE);
        if (first == 'f') return literal("false", Boolean.FALSE);
        if (first == 'n') return literal("null", null);
        return number();
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, position)) throw new IllegalArgumentException(INVALID);
        position += word.length();
        return value;
    }

    private Double number() {
        int start = position;
        while (position < text.length() && "+-0123456789.eE".indexOf(text.charAt(position)) >= 0) position++;
        String digits = text.substring(start, position);
        if (!digits.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) {
            throw new IllegalArgumentException(INVALID);
        }
        double parsed = Double.parseDouble(digits);
        if (!Double.isFinite(parsed)) throw new IllegalArgumentException(INVALID);
        return parsed;
    }

    private String string() {
        expect('"');
        StringBuilder value = new StringBuilder();
        while (true) {
            char current = next();
            if (current == '"') return value.toString();
            if (current < 0x20) throw new IllegalArgumentException(INVALID);
            if (current != '\\') {
                value.append(current);
                continue;
            }
            char escaped = next();
            int simple = "\"\\/bfnrt".indexOf(escaped);
            if (simple >= 0) {
                value.append("\"\\/\b\f\n\r\t".charAt(simple));
            } else if (escaped == 'u') {
                value.append(unicode());
            } else {
                throw new IllegalArgumentException(INVALID);
            }
        }
    }

    private char unicode() {
        if (position + 4 > text.length()) throw new IllegalArgumentException(INVALID);
        int code = 0;
        for (int index = 0; index < 4; index++) {
            int digit = Character.digit(text.charAt(position++), 16);
            if (digit < 0) throw new IllegalArgumentException(INVALID);
            code = code * 16 + digit;
        }
        return (char) code;
    }

    private void skipWhitespace() {
        while (position < text.length() && " \t\r\n".indexOf(text.charAt(position)) >= 0) position++;
    }

    private char peek() {
        if (position >= text.length()) throw new IllegalArgumentException(INVALID);
        return text.charAt(position);
    }

    private char next() {
        char current = peek();
        position++;
        return current;
    }

    private void expect(char expected) {
        if (next() != expected) throw new IllegalArgumentException(INVALID);
    }
}
