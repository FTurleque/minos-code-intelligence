package com.minos.application.remote;

import com.minos.remote.RemoteIndexingRuntime.VerifiedArtifactEvidence;

import java.util.Objects;

import static com.minos.domain.Preconditions.requireText;

/** The verified evidence of one artifact a worker produced (manifest, digests, cache), as the surfaces show it. */
public record ArtifactEvidenceSummary(
        String providerId,
        String providerVersion,
        String language,
        String workerId,
        String isolation,
        String networkPolicy,
        boolean networkDenyEnforced,
        String artifactSha256,
        String bundleSha256,
        String cacheKey,
        boolean cacheHit,
        String projectRelativeRoot
) {
    public ArtifactEvidenceSummary {
        requireText(providerId, "providerId");
        requireText(providerVersion, "providerVersion");
        requireText(language, "language");
        requireText(workerId, "workerId");
        requireText(isolation, "isolation");
        requireText(networkPolicy, "networkPolicy");
        requireText(artifactSha256, "artifactSha256");
        requireText(bundleSha256, "bundleSha256");
        requireText(cacheKey, "cacheKey");
        // projectRelativeRoot is "" for the root scope: not blank-checked.
        Objects.requireNonNull(projectRelativeRoot, "projectRelativeRoot");
    }

    static ArtifactEvidenceSummary of(VerifiedArtifactEvidence value) {
        var manifest = value.manifest();
        return new ArtifactEvidenceSummary(
                manifest.providerId(), manifest.providerVersion(), manifest.language().name(), manifest.workerId(),
                manifest.isolation().name(), manifest.networkPolicy().name(), manifest.networkDenyEnforced(),
                manifest.artifactSha256(), value.bundleSha256(), value.cacheKey(), value.cacheHit(),
                manifest.projectRelativeRoot());
    }
}
