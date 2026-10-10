package com.minos.output.json;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal deterministic JSON encoder shared by transport renderers, and the single place in the
 * code base where text is escaped for JSON.
 *
 * <p>Contract:</p>
 * <ul>
 *   <li><b>Key order.</b> A {@link Map} is written in its own iteration order, so the caller owns
 *       the order: pass a {@link java.util.LinkedHashMap} (see {@link #object(Object...)}) or a
 *       {@link java.util.TreeMap}. {@code Map.of}, {@code Map.copyOf}, {@code Set.of} and
 *       {@code Set.copyOf} with two or more elements iterate in an order drawn at random when the
 *       JVM starts, so they must never reach this encoder.</li>
 *   <li><b>Numbers.</b> JSON (RFC 8259) has no text for {@code NaN} or the infinities, so a
 *       {@link Double} or {@link Float} that is not finite is <em>refused</em> with a
 *       {@link NonFiniteNumberException} rather than written as invalid JSON or silently turned
 *       into {@code null} (which would hide a wrong upstream value). Every other number keeps its
 *       {@code toString()} form.</li>
 *   <li><b>Strings.</b> {@code "} and {@code \}, every control character U+0000 to U+001F, lone
 *       surrogates (which UTF-8 cannot carry) and U+2028/U+2029 (which break JavaScript
 *       consumers) are escaped; valid surrogate pairs are written as they are.</li>
 * </ul>
 */
public final class DeterministicJson {

    private static final char LINE_SEPARATOR = 0x2028;
    private static final char PARAGRAPH_SEPARATOR = 0x2029;

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

    /**
     * Builds an ordered JSON object from alternating keys and values; the pairs are written in the
     * order given.
     */
    public static Map<String, Object> object(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("object requires key/value pairs");
        }
        Map<String, Object> value = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            value.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
        }
        return value;
    }

    /**
     * Text of a number for hand-written JSON: the {@code Double.toString} form of a finite value.
     *
     * @throws NonFiniteNumberException if the value is {@code NaN} or infinite
     */
    public static String number(double value) {
        if (!Double.isFinite(value)) throw new NonFiniteNumberException();
        return Double.toString(value);
    }

    private static String numberText(Number number) {
        if (number instanceof Double doubleValue) {
            return number(doubleValue);
        }
        if (number instanceof Float floatValue) {
            // Not widened to double: 0.1f must stay 0.1 (Float.toString), not 0.10000000149011612.
            if (!Float.isFinite(floatValue)) throw new NonFiniteNumberException();
            return Float.toString(floatValue);
        }
        return number.toString();
    }

    private static void append(JsonOutput output, Object value) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String string) {
            quote(output, string);
        } else if (value instanceof Number number) {
            output.append(numberText(number));
        } else if (value instanceof Boolean) {
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

    /** Returns {@code value} as a quoted, escaped JSON string. */
    public static String quote(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 2);
        quote(builder, value);
        return builder.toString();
    }

    /** Appends {@code value} to {@code builder} as a quoted, escaped JSON string. */
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
                    if (Character.isHighSurrogate(character)
                            && index + 1 < value.length()
                            && Character.isLowSurrogate(value.charAt(index + 1))) {
                        output.appendCodePoint(Character.toCodePoint(character, value.charAt(++index)));
                    } else if (character < 0x20 || Character.isSurrogate(character)
                            || character == LINE_SEPARATOR || character == PARAGRAPH_SEPARATOR) {
                        output.append("\\u%04x".formatted((int) character));
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        output.append('"');
    }

    /** A {@code NaN} or infinite {@code Double}/{@code Float} reached the encoder; JSON cannot carry it. */
    public static final class NonFiniteNumberException extends IllegalStateException {
        private NonFiniteNumberException() {
            super("deterministic JSON cannot represent a non-finite number (NaN or Infinity)");
        }
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
            account(utf8Bytes(codePoint));
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

        private static int utf8Bytes(int codePoint) {
            if (codePoint <= 0x7f) return 1;
            if (codePoint <= 0x7ff) return 2;
            if (codePoint <= 0xffff) return 3;
            return 4;
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
