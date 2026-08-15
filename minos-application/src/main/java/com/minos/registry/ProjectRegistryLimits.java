package com.minos.registry;

import java.nio.charset.StandardCharsets;

/** Shared input invariants for registry values persisted by every backend. */
public final class ProjectRegistryLimits {
    public static final int MAX_NAME_UTF8_BYTES = 16 * 1024;

    private ProjectRegistryLimits() {
    }

    public static String requireName(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(label + " must not contain NUL");
        }
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_NAME_UTF8_BYTES) {
            throw new IllegalArgumentException(label + " exceeds UTF-8 byte limit: "
                    + bytes + "/" + MAX_NAME_UTF8_BYTES);
        }
        return value;
    }
}
