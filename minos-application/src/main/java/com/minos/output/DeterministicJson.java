package com.minos.output;

import java.util.Iterator;
import java.util.Map;

/** Minimal deterministic JSON encoder shared by transport renderers. */
public final class DeterministicJson {

    private DeterministicJson() {
    }

    public static String render(Object value) {
        return render(value, Long.MAX_VALUE);
    }

    /** Renders valid JSON or fails before the UTF-8 result can exceed the supplied byte budget. */
    public static String render(Object value, long maximumUtf8Bytes) {
        if (maximumUtf8Bytes < 1L) {
            throw new IllegalArgumentException("maximumUtf8Bytes must be positive");
        }
        JsonOutput output = new JsonOutput(new StringBuilder(), maximumUtf8Bytes);
        append(output, value);
        return output.toString();
    }

    private static void append(JsonOutput output, Object value) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String string) {
            quote(output, string);
        } else if (value instanceof Number || value instanceof Boolean) {
            output.append(value.toString());
        } else if (value instanceof Enum<?> enumeration) {
            quote(output, enumeration.name());
        } else if (value instanceof Map<?, ?> map) {
            appendMap(output, map);
        } else if (value instanceof Iterable<?> iterable) {
            appendIterable(output, iterable);
        } else {
            quote(output, value.toString());
        }
    }

    private static void appendMap(JsonOutput output, Map<?, ?> map) {
        output.append('{');
        Iterator<? extends Map.Entry<?, ?>> iterator = map.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<?, ?> entry = iterator.next();
            quote(output, String.valueOf(entry.getKey()));
            output.append(':');
            append(output, entry.getValue());
            if (iterator.hasNext()) {
                output.append(',');
            }
        }
        output.append('}');
    }

    private static void appendIterable(JsonOutput output, Iterable<?> iterable) {
        output.append('[');
        Iterator<?> iterator = iterable.iterator();
        while (iterator.hasNext()) {
            append(output, iterator.next());
            if (iterator.hasNext()) {
                output.append(',');
            }
        }
        output.append(']');
    }

    public static void quote(StringBuilder builder, String value) {
        quote(new JsonOutput(builder, Long.MAX_VALUE), value);
    }

    private static void quote(JsonOutput output, String value) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20) {
                        output.append("\\u%04x".formatted((int) character));
                    } else if (Character.isHighSurrogate(character)
                            && index + 1 < value.length()
                            && Character.isLowSurrogate(value.charAt(index + 1))) {
                        output.appendCodePoint(Character.toCodePoint(character, value.charAt(++index)));
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        output.append('"');
    }

    public static final class OutputBudgetExceededException extends IllegalStateException {
        private OutputBudgetExceededException() {
            super("deterministic JSON exceeds the configured UTF-8 byte budget");
        }
    }

    private static final class JsonOutput {
        private final StringBuilder builder;
        private final long maximumUtf8Bytes;
        private long utf8Bytes;

        private JsonOutput(StringBuilder builder, long maximumUtf8Bytes) {
            this.builder = builder;
            this.maximumUtf8Bytes = maximumUtf8Bytes;
        }

        private void append(char value) {
            account(Character.isSurrogate(value) ? 1 : utf8Bytes(value));
            builder.append(value);
        }

        private void appendCodePoint(int codePoint) {
            account(codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4);
            builder.appendCodePoint(codePoint);
        }

        private void append(String value) {
            long bytes = 0L;
            for (int index = 0; index < value.length(); index++) {
                char current = value.charAt(index);
                if (Character.isHighSurrogate(current)
                        && index + 1 < value.length()
                        && Character.isLowSurrogate(value.charAt(index + 1))) {
                    bytes = safeAdd(bytes, 4L);
                    index++;
                } else {
                    bytes = safeAdd(bytes, Character.isSurrogate(current) ? 1L : utf8Bytes(current));
                }
            }
            account(bytes);
            builder.append(value);
        }

        private void account(long bytes) {
            utf8Bytes = safeAdd(utf8Bytes, bytes);
            if (utf8Bytes > maximumUtf8Bytes) throw new OutputBudgetExceededException();
        }

        private static int utf8Bytes(char value) {
            return value <= 0x7f ? 1 : value <= 0x7ff ? 2 : 3;
        }

        private static long safeAdd(long left, long right) {
            if (right > Long.MAX_VALUE - left) throw new OutputBudgetExceededException();
            return left + right;
        }

        @Override
        public String toString() {
            return builder.toString();
        }
    }
}
