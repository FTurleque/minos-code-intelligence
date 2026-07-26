package com.minos.cli;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Encodeur JSON minimal et déterministe pour les sorties CLI.
 *
 * <p>Il n'introduit aucune dépendance de sérialisation dans le cœur MINOS et
 * respecte l'ordre des {@link Map} fourni par les commandes.</p>
 */
final class CliJson {

    private CliJson() {
    }

    static String render(Object value) {
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

    static void quote(StringBuilder builder, String value) {
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
