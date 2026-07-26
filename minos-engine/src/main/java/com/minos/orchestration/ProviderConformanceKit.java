package com.minos.orchestration;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Standardized, deterministic provider qualification profile. */
public final class ProviderConformanceKit {

    public ConformanceResult evaluate(IndexerProvider provider) {
        Objects.requireNonNull(provider, "provider");
        IndexerDescriptor descriptor = provider.descriptor();
        ProviderCapabilityProfile profile = provider.capabilityProfile();
        EnumMap<CapabilitySupportLevel, Integer> counts = new EnumMap<>(CapabilitySupportLevel.class);
        for (CapabilitySupportLevel level : CapabilitySupportLevel.values()) {
            counts.put(level, 0);
        }
        int points = 0;
        for (IndexerCapability capability : IndexerCapability.values()) {
            CapabilitySupportLevel level = profile.supportOf(capability);
            counts.put(level, counts.get(level) + 1);
            points += switch (level) {
                case FULL -> 3;
                case PARTIAL -> 2;
                case EXPERIMENTAL -> 1;
                case UNSUPPORTED -> 0;
            };
        }
        int maxPoints = IndexerCapability.values().length * 3;
        int score = maxPoints == 0 ? 0 : (int) Math.round(points * 100.0 / maxPoints);
        Map<String, String> capabilities = new java.util.TreeMap<>();
        for (IndexerCapability capability : IndexerCapability.values()) {
            capabilities.put(capability.name(), profile.supportOf(capability).name());
        }
        return new ConformanceResult(
                descriptor.id(),
                descriptor.version(),
                descriptor.languages().stream().map(Enum::name).sorted().toList(),
                descriptor.buildSystems().stream().map(Enum::name).sorted().toList(),
                Map.copyOf(capabilities),
                Map.copyOf(counts),
                score,
                descriptor.limitations()
        );
    }

    public record ConformanceResult(
            String providerId,
            String version,
            List<String> languages,
            List<String> buildSystems,
            Map<String, String> capabilities,
            Map<CapabilitySupportLevel, Integer> counts,
            int scorePercent,
            List<String> limitations
    ) {
        public ConformanceResult {
            if (providerId == null || providerId.isBlank()) throw new IllegalArgumentException("providerId must not be blank");
            if (version == null || version.isBlank()) throw new IllegalArgumentException("version must not be blank");
            languages = List.copyOf(Objects.requireNonNull(languages, "languages"));
            buildSystems = List.copyOf(Objects.requireNonNull(buildSystems, "buildSystems"));
            capabilities = Map.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
            counts = Map.copyOf(Objects.requireNonNull(counts, "counts"));
            limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations"));
            if (scorePercent < 0 || scorePercent > 100) throw new IllegalArgumentException("scorePercent out of range");
        }
    }
}
