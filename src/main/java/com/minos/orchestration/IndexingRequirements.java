package com.minos.orchestration;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Exigences explicites utilisées pour négocier un indexeur.
 */
public record IndexingRequirements(
        Set<IndexerCapability> requiredCapabilities,
        boolean allowExperimental
) {

    public IndexingRequirements {
        Objects.requireNonNull(requiredCapabilities, "requiredCapabilities");
        requiredCapabilities = requiredCapabilities.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(requiredCapabilities));
    }

    public static IndexingRequirements baseline() {
        return new IndexingRequirements(
                EnumSet.of(IndexerCapability.SYMBOLS, IndexerCapability.REFERENCES),
                false
        );
    }

    public static IndexingRequirements requiring(IndexerCapability first, IndexerCapability... additional) {
        Objects.requireNonNull(first, "first");
        EnumSet<IndexerCapability> capabilities = EnumSet.of(first, additional);
        return new IndexingRequirements(capabilities, false);
    }
}
