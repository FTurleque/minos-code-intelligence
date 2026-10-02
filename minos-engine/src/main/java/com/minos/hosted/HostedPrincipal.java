package com.minos.hosted;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Tenant-scoped principal membership. Authentication remains a separate boundary. */
public record HostedPrincipal(String principalId, String displayName, HostedRole role, Instant createdAt) {
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:@+\\-]{0,127}");

    public HostedPrincipal {
        principalId = safeId(principalId, "principalId");
        displayName = text(displayName, "displayName", 256);
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static String safeId(String value, String field) {
        String normalized = text(value, field, 128);
        if (!SAFE_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
        return normalized;
    }

    public static String text(String value, String field, int maximum) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        String normalized = value.trim();
        if (normalized.length() > maximum || normalized.indexOf('\0') >= 0
                || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0
                || normalized.indexOf('\t') >= 0) {
            throw new IllegalArgumentException(field + " is invalid or exceeds its limit");
        }
        return normalized;
    }
}
