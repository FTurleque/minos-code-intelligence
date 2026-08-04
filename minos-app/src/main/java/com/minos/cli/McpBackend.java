package com.minos.cli;

import java.util.Locale;

/** Runtime backend selected for the stable MINOS MCP entry point. */
public enum McpBackend {
    NATIVE,
    DOCKER;

    public String configurationValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static McpBackend parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("MCP backend must not be blank");
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "native" -> NATIVE;
            case "docker" -> DOCKER;
            default -> throw new IllegalArgumentException("unsupported MCP backend: " + value);
        };
    }
}
