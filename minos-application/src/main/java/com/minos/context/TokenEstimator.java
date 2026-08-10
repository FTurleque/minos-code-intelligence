package com.minos.context;

/**
 * Estimation locale et déterministe du volume de contexte.
 *
 * <p>La politique utilise quatre octets UTF-8 par token estimé. Elle ne prétend
 * pas reproduire un tokenizer de modèle particulier ; elle fournit une borne
 * homogène et mesurable sans dépendance à un LLM ou à un service externe.</p>
 */
public final class TokenEstimator {

    private static final int UTF8_BYTES_PER_TOKEN = 4;

    private TokenEstimator() {
    }

    public static int estimate(CharSequence value) {
        if (value == null || value.isEmpty()) return 0;
        long bytes = utf8Length(value, Long.MAX_VALUE);
        long tokens = Math.max(1L, (bytes + UTF8_BYTES_PER_TOKEN - 1L) / UTF8_BYTES_PER_TOKEN);
        return tokens > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) tokens;
    }

    public static String truncate(CharSequence value, int maxTokens) {
        if (value == null || value.isEmpty() || maxTokens <= 0) return "";
        long maxBytes = (long) maxTokens * UTF8_BYTES_PER_TOKEN;
        int boundary = boundaryWithinUtf8Bytes(value, maxBytes);
        if (boundary >= value.length()) return value instanceof String string ? string : value.toString();
        return value.subSequence(0, boundary).toString();
    }

    private static long utf8Length(CharSequence value, long stopAfter) {
        long bytes = 0L;
        for (int offset = 0; offset < value.length();) {
            char first = value.charAt(offset);
            int codePoint;
            int chars;
            if (Character.isHighSurrogate(first) && offset + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(offset + 1))) {
                codePoint = Character.toCodePoint(first, value.charAt(offset + 1));
                chars = 2;
            } else {
                codePoint = first;
                chars = 1;
            }
            bytes += utf8Bytes(codePoint);
            if (bytes > stopAfter) return bytes;
            offset += chars;
        }
        return bytes;
    }

    private static int boundaryWithinUtf8Bytes(CharSequence value, long maximumBytes) {
        long bytes = 0L;
        int offset = 0;
        while (offset < value.length()) {
            char first = value.charAt(offset);
            int codePoint;
            int chars;
            if (Character.isHighSurrogate(first) && offset + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(offset + 1))) {
                codePoint = Character.toCodePoint(first, value.charAt(offset + 1));
                chars = 2;
            } else {
                codePoint = first;
                chars = 1;
            }
            int encoded = utf8Bytes(codePoint);
            if (bytes + encoded > maximumBytes) break;
            bytes += encoded;
            offset += chars;
        }
        return offset;
    }

    private static int utf8Bytes(int codePoint) {
        if (codePoint <= 0x7f) return 1;
        if (codePoint <= 0x7ff) return 2;
        if (codePoint <= 0xffff) return 3;
        return 4;
    }
}
