package com.minos.remote;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.remote.DistributedIndexing.WorkerIsolation;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Versioned, secret-free provenance envelope for one transported provider artifact. */
public record DistributedArtifactManifest(
        String format,
        UUID runId,
        UUID projectId,
        String sourceRepository,
        String sourceCommit,
        Language language,
        String providerId,
        String providerVersion,
        String workerId,
        WorkerIsolation isolation,
        WorkerNetworkPolicy networkPolicy,
        boolean networkDenyEnforced,
        Instant startedAt,
        Instant completedAt,
        String artifactPath,
        long artifactSize,
        String artifactSha256
) {

    public static final String FORMAT_V1 = "minos-distributed-artifact-v1";
    public static final String ARTIFACT_PATH = "index.scip";

    public DistributedArtifactManifest {
        if (!FORMAT_V1.equals(format)) {
            throw new IllegalArgumentException("unsupported distributed artifact format: " + format);
        }
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(projectId, "projectId");
        sourceRepository = requireText(sourceRepository, "sourceRepository");
        if (sourceRepository.contains("@") || sourceRepository.contains("?") || sourceRepository.contains("#")) {
            throw new IllegalArgumentException("sourceRepository must be canonical and secret-free");
        }
        if (sourceCommit == null || !sourceCommit.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("sourceCommit must be a lowercase complete Git SHA-1");
        }
        Objects.requireNonNull(language, "language");
        providerId = requireText(providerId, "providerId");
        providerVersion = requireText(providerVersion, "providerVersion");
        workerId = requireText(workerId, "workerId");
        Objects.requireNonNull(isolation, "isolation");
        Objects.requireNonNull(networkPolicy, "networkPolicy");
        if (networkPolicy == WorkerNetworkPolicy.DENY && !networkDenyEnforced) {
            throw new IllegalArgumentException("DENY network policy requires an enforced worker boundary");
        }
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(completedAt, "completedAt");
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("completedAt must not be before startedAt");
        }
        if (!ARTIFACT_PATH.equals(artifactPath)) {
            throw new IllegalArgumentException("artifactPath must be exactly " + ARTIFACT_PATH);
        }
        if (artifactSize < 1) {
            throw new IllegalArgumentException("artifactSize must be positive");
        }
        if (artifactSha256 == null || !artifactSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("artifactSha256 must be a lowercase SHA-256 value");
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}
