package com.minos.orchestration;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Standardized, deterministic provider qualification profile. */
public final class ProviderConformanceKit {

    public ConformanceResult evaluate(IndexerProvider provider) {
        Objects.requireNonNull(provider, "provider");
        IndexerDescriptor descriptor = Objects.requireNonNull(provider.descriptor(), "descriptor");
        ProviderCapabilityProfile profile = Objects.requireNonNull(provider.capabilityProfile(), "capabilityProfile");
        ProviderOperationalProfile operational = Objects.requireNonNull(
                provider.operationalProfile(), "operationalProfile");
        requireSameProviderId(descriptor.id(), profile.providerId(), "capability profile");
        requireSameProviderId(descriptor.id(), operational.providerId(), "operational profile");

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
                descriptor.qualification().name(),
                descriptor.languages().stream().map(Enum::name).sorted().toList(),
                descriptor.buildSystems().stream().map(Enum::name).sorted().toList(),
                Map.copyOf(capabilities),
                Map.copyOf(counts),
                score,
                descriptor.limitations(),
                operational.explicit(),
                operational.qualificationPlatforms().stream().map(Enum::name).sorted().toList(),
                operational.runtimeRequirements(),
                operational.readinessBehavior(),
                operational.installationBehavior(),
                operational.stableIdentityBehavior(),
                operational.provenanceBehavior()
        );
    }

    private static void requireSameProviderId(String descriptorId, String actualId, String label) {
        if (!descriptorId.equals(actualId)) {
            throw new IllegalArgumentException(
                    label + " providerId mismatch: descriptor=" + descriptorId + ", profile=" + actualId);
        }
    }

    public record ConformanceResult(
            String providerId,
            String version,
            String qualification,
            List<String> languages,
            List<String> buildSystems,
            Map<String, String> capabilities,
            Map<CapabilitySupportLevel, Integer> counts,
            int scorePercent,
            List<String> limitations,
            boolean operationalProfileExplicit,
            List<String> qualificationPlatforms,
            List<String> runtimeRequirements,
            String readinessBehavior,
            String installationBehavior,
            String stableIdentityBehavior,
            String provenanceBehavior
    ) {
        public ConformanceResult {
            if (providerId == null || providerId.isBlank()) throw new IllegalArgumentException("providerId must not be blank");
            if (version == null || version.isBlank()) throw new IllegalArgumentException("version must not be blank");
            if (qualification == null || qualification.isBlank()) throw new IllegalArgumentException("qualification must not be blank");
            languages = List.copyOf(Objects.requireNonNull(languages, "languages"));
            buildSystems = List.copyOf(Objects.requireNonNull(buildSystems, "buildSystems"));
            capabilities = Map.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
            counts = Map.copyOf(Objects.requireNonNull(counts, "counts"));
            limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations"));
            qualificationPlatforms = List.copyOf(Objects.requireNonNull(qualificationPlatforms, "qualificationPlatforms"));
            runtimeRequirements = List.copyOf(Objects.requireNonNull(runtimeRequirements, "runtimeRequirements"));
            if (readinessBehavior == null || readinessBehavior.isBlank()) throw new IllegalArgumentException("readinessBehavior must not be blank");
            if (installationBehavior == null || installationBehavior.isBlank()) throw new IllegalArgumentException("installationBehavior must not be blank");
            if (stableIdentityBehavior == null || stableIdentityBehavior.isBlank()) throw new IllegalArgumentException("stableIdentityBehavior must not be blank");
            if (provenanceBehavior == null || provenanceBehavior.isBlank()) throw new IllegalArgumentException("provenanceBehavior must not be blank");
            if (scorePercent < 0 || scorePercent > 100) throw new IllegalArgumentException("scorePercent out of range");
        }
    }
}
