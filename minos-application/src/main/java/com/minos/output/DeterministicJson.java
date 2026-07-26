package com.minos.output;

import java.util.Iterator;
import java.util.Map;

/** Minimal deterministic JSON encoder shared by transport renderers. */
public final class DeterministicJson {

    private DeterministicJson() {
    }

    public static String render(Object value) {
        StringBuilder builder = new StringBuilder();
        append(builder, value);
        return builder.toString();
    }

    private static void append(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof String string) {
            quote(builder, string);
        } else if (value instanceof Number || value instanceof Boolean) {
            builder.append(value);
        } else if (value instanceof Enum<?> enumeration) {
            quote(builder, enumeration.name());
        } else if (value instanceof Map<?, ?> map) {
            appendMap(builder, map);
        } else if (value instanceof Iterable<?> iterable) {
            appendIterable(builder, iterable);
        } else {
            quote(builder, value.toString());
        }
    }

    private static void appendMap(StringBuilder builder, Map<?, ?> map) {
        builder.append('{');
        Iterator<? extends Map.Entry<?, ?>> iterator = map.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<?, ?> entry = iterator.next();
            quote(builder, String.valueOf(entry.getKey()));
            builder.append(':');
            append(builder, entry.getValue());
            if (iterator.hasNext()) {
                builder.append(',');
            }
        }
        builder.append('}');
    }

    private static void appendIterable(StringBuilder builder, Iterable<?> iterable) {
        builder.append('[');
        Iterator<?> iterator = iterable.iterator();
        while (iterator.hasNext()) {
            append(builder, iterator.next());
            if (iterator.hasNext()) {
                builder.append(',');
            }
        }
        builder.append(']');
    }

    public static void quote(StringBuilder builder, String value) {
        builder.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (character < 0x20) {
                        builder.append("\\u%04x".formatted((int) character));
                    } else {
                        builder.append(character);
                    }
                }
            }
        }
        builder.append('"');
    }
}
