package com.minos.orchestration;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit operational evidence attached to an indexer provider.
 *
 * <p>The capability profile answers what facts the provider can emit. This profile
 * answers where/how the runtime is qualified and how MINOS preserves identity and
 * provenance. Keeping the two contracts separate avoids turning runtime readiness
 * into a semantic capability claim.</p>
 */
public record ProviderOperationalProfile(
        String providerId,
        boolean explicit,
        Set<ProviderPlatform> qualificationPlatforms,
        List<String> runtimeRequirements,
        String readinessBehavior,
        String installationBehavior,
        String stableIdentityBehavior,
        String provenanceBehavior
) {
    public ProviderOperationalProfile {
        providerId = requireText(providerId, "providerId");
        Objects.requireNonNull(qualificationPlatforms, "qualificationPlatforms");
        qualificationPlatforms = qualificationPlatforms.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(qualificationPlatforms));
        runtimeRequirements = List.copyOf(Objects.requireNonNull(runtimeRequirements, "runtimeRequirements"));
        if (runtimeRequirements.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("runtimeRequirements must not contain blank values");
        }
        readinessBehavior = requireText(readinessBehavior, "readinessBehavior");
        installationBehavior = requireText(installationBehavior, "installationBehavior");
        stableIdentityBehavior = requireText(stableIdentityBehavior, "stableIdentityBehavior");
        provenanceBehavior = requireText(provenanceBehavior, "provenanceBehavior");
        if (explicit && qualificationPlatforms.isEmpty()) {
            throw new IllegalArgumentException("explicit operational profile must claim at least one qualification platform");
        }
    }

    /** Compatibility fallback for third-party/legacy providers that predate M24. */
    public static ProviderOperationalProfile legacy(String providerId) {
        return new ProviderOperationalProfile(
                providerId,
                false,
                Set.of(),
                List.of(),
                "legacy provider did not declare readiness behavior",
                "legacy provider did not declare installation behavior",
                "legacy provider did not declare stable identity behavior",
                "legacy provider did not declare provenance behavior"
        );
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}
