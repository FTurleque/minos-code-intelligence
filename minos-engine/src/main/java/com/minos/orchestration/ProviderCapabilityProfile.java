package com.minos.orchestration;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Exhaustive qualification profile for one provider.
 *
 * <p>Every {@link IndexerCapability} must be present. Missing entries are rejected
 * so public surfaces never infer support from absence.</p>
 */
public record ProviderCapabilityProfile(
        String providerId,
        Map<IndexerCapability, CapabilitySupportLevel> support,
        List<String> limitations
) {
    public ProviderCapabilityProfile {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
        Objects.requireNonNull(support, "support");
        EnumMap<IndexerCapability, CapabilitySupportLevel> copy = new EnumMap<>(IndexerCapability.class);
        copy.putAll(support);
        for (IndexerCapability capability : IndexerCapability.values()) {
            if (!copy.containsKey(capability) || copy.get(capability) == null) {
                throw new IllegalArgumentException("missing explicit support level for " + capability);
            }
        }
        support = Map.copyOf(copy);
        limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations"));
    }

    public CapabilitySupportLevel supportOf(IndexerCapability capability) {
        return support.get(Objects.requireNonNull(capability, "capability"));
    }

    public boolean usable(IndexerCapability capability, boolean allowExperimental) {
        return switch (supportOf(capability)) {
            case FULL, PARTIAL -> true;
            case EXPERIMENTAL -> allowExperimental;
            case UNSUPPORTED -> false;
        };
    }
}
