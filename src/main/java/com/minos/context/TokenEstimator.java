package com.minos.context;

import java.nio.charset.StandardCharsets;

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
        if (value == null || value.isEmpty()) {
            return 0;
        }
        int bytes = value.toString().getBytes(StandardCharsets.UTF_8).length;
        return Math.max(1, (bytes + UTF8_BYTES_PER_TOKEN - 1) / UTF8_BYTES_PER_TOKEN);
    }

    public static String truncate(CharSequence value, int maxTokens) {
        if (value == null || value.isEmpty() || maxTokens <= 0) {
            return "";
        }
        String text = value.toString();
        if (estimate(text) <= maxTokens) {
            return text;
        }

        int low = 0;
        int high = text.length();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            int safeMiddle = safeBoundary(text, middle);
            if (estimate(text.substring(0, safeMiddle)) <= maxTokens) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        int boundary = safeBoundary(text, low);
        while (boundary > 0 && estimate(text.substring(0, boundary)) > maxTokens) {
            boundary = safeBoundary(text, boundary - 1);
        }
        return text.substring(0, boundary);
    }

    private static int safeBoundary(String value, int boundary) {
        int safe = Math.max(0, Math.min(boundary, value.length()));
        if (safe > 0 && safe < value.length()
                && Character.isHighSurrogate(value.charAt(safe - 1))
                && Character.isLowSurrogate(value.charAt(safe))) {
            return safe - 1;
        }
        return safe;
    }
}
