package io.github.fturleque.minos.domain;

import java.util.Objects;

/**
 * Provenance d'un fait ou d'une occurrence.
 */
public record Origin(
        String providerId,
        String providerType,
        String providerVersion,
        String indexRunId,
        OriginType sourceType) {

    public Origin {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
        Objects.requireNonNull(sourceType, "sourceType");
    }
}
