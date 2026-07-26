package com.minos.output;

import java.util.Locale;

/**
 * Formats de sortie publics pour les résultats de symboles.
 */
public enum SymbolOutputFormat {
    TEXT,
    JSON;

    public static SymbolOutputFormat parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("output format must not be blank");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "unsupported output format: " + value + " (expected text or json)",
                    exception
            );
        }
    }
}
