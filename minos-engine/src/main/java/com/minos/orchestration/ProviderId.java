package com.minos.orchestration;

/**
 * Canonical invariant for provider/indexer identifiers.
 *
 * <p>Provider identifiers are used in filesystem paths and operational metadata, so they must
 * never contain path separators, traversal components, shell metacharacters or unbounded text.</p>
 */
public final class ProviderId {

    public static final String SAFE_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}";

    private ProviderId() {
    }

    public static String require(String value) {
        if (value == null || !value.matches(SAFE_PATTERN)) {
            throw new IllegalArgumentException("provider id must match " + SAFE_PATTERN);
        }
        return value;
    }
}
