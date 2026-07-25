package com.minos.cli;

import java.util.Locale;

/** Output formats supported by the architecture surface. */
public enum ArchitectureOutputFormat {
    TEXT,
    JSON,
    MERMAID,
    DOT;

    public static ArchitectureOutputFormat parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("output format must not be blank");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "unsupported architecture output format: " + value +
                            " (expected text, json, mermaid or dot)",
                    exception
            );
        }
    }

    public boolean isGraph() {
        return this == MERMAID || this == DOT;
    }
}
