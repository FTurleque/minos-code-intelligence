package com.minos.domain;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Stable reference to a MINOS code entity. */
public record CodeEntityRef(CodeEntityType type, String id) {
    public static final int MAX_ID_UTF8_BYTES = 64 * 1024;

    public CodeEntityRef {
        Objects.requireNonNull(type, "type");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        if (id.getBytes(StandardCharsets.UTF_8).length > MAX_ID_UTF8_BYTES) {
            throw new IllegalArgumentException("id exceeds UTF-8 byte limit: " + MAX_ID_UTF8_BYTES);
        }
    }
}
