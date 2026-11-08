package com.minos.remote;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.remote.DistributedIndexing.WorkerIsolation;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import static com.minos.domain.Preconditions.requireText;

/**
 * Versioned, secret-free provenance envelope for one transported provider artifact.
 *
 * <p>The {@link DistributedArtifactSource source} is sealed: formats v1 and v2 carry a
 * {@link DistributedArtifactSource.RemoteRevision remote revision}; format v3 carries the
 * {@link DistributedArtifactSource.LocalProject fingerprint of a local project} indexed through the WSL2 route.
 * Each format is accepted only by the executor of its route.</p>
 */
public record DistributedArtifactManifest(
        String format,
        UUID runId,
        UUID projectId,
        String projectRelativeRoot,
        DistributedArtifactSource source,
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
    public static final String FORMAT_V2 = "minos-distributed-artifact-v2";
    public static final String FORMAT_V3 = "minos-distributed-artifact-v3";
    public static final String ARTIFACT_PATH = "index.scip";

    public DistributedArtifactManifest {
        requireKnownFormat(format);
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(projectId, "projectId");
        projectRelativeRoot = normalizeManifestScope(projectRelativeRoot);
        requireRootScopeForV1(format, projectRelativeRoot);
        Objects.requireNonNull(source, "source");
        if (FORMAT_V3.equals(format) != source instanceof DistributedArtifactSource.LocalProject) {
            throw new IllegalArgumentException(
                    "format v3 names a local project, formats v1 and v2 a remote revision");
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

    /** A remote manifest (formats v1 and v2): a canonical repository and a complete Git commit. */
    public DistributedArtifactManifest(
            String format,
            UUID runId,
            UUID projectId,
            String projectRelativeRoot,
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
        this(format, runId, projectId, projectRelativeRoot,
                new DistributedArtifactSource.RemoteRevision(sourceRepository, sourceCommit), language, providerId,
                providerVersion, workerId, isolation, networkPolicy, networkDenyEnforced, startedAt, completedAt,
                artifactPath, artifactSize, artifactSha256);
    }

    /**
     * A format v3 manifest: a local project identified by its fingerprint, produced under an enforced {@code DENY}
     * network policy.
     */
    public static DistributedArtifactManifest localWsl(
            UUID runId,
            UUID projectId,
            String projectRelativeRoot,
            String projectFingerprint,
            Language language,
            String providerId,
            String providerVersion,
            String workerId,
            WorkerIsolation isolation,
            Instant startedAt,
            Instant completedAt,
            long artifactSize,
            String artifactSha256
    ) {
        return new DistributedArtifactManifest(FORMAT_V3, runId, projectId, projectRelativeRoot,
                new DistributedArtifactSource.LocalProject(projectFingerprint), language, providerId,
                providerVersion, workerId, isolation, WorkerNetworkPolicy.DENY, true, startedAt, completedAt,
                ARTIFACT_PATH, artifactSize, artifactSha256);
    }

    /** The repository of a remote source; a local source has none and is refused. */
    public String sourceRepository() {
        return remote().repository();
    }

    /** The commit of a remote source; a local source has none and is refused. */
    public String sourceCommit() {
        return remote().commit();
    }

    /** The project fingerprint of a local source; a remote source has none and is refused. */
    public String projectFingerprint() {
        if (source instanceof DistributedArtifactSource.LocalProject local) {
            return local.fingerprint();
        }
        throw new IllegalStateException("a remote artifact source has no project fingerprint");
    }

    private DistributedArtifactSource.RemoteRevision remote() {
        if (source instanceof DistributedArtifactSource.RemoteRevision remote) {
            return remote;
        }
        throw new IllegalStateException("a local artifact source has no remote revision");
    }

    private static String normalizeManifestScope(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("projectRelativeRoot must not contain NUL bytes");
        }
        if (value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(
                    "projectRelativeRoot must use '/' as the path separator, not '\\'");
        }
        // Reject leading '/' explicitly: on Windows Path.isAbsolute() returns false for root-relative paths.
        if (value.charAt(0) == '/') {
            throw new IllegalArgumentException(
                    "projectRelativeRoot must stay relative to the registered project root");
        }
        if (value.length() > 1024) {
            throw new IllegalArgumentException("projectRelativeRoot must not exceed 1024 characters");
        }
        Path path;
        try {
            path = Path.of(value).normalize();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "projectRelativeRoot contains invalid path characters", exception);
        }
        if (path.isAbsolute() || path.startsWith("..")) {
            throw new IllegalArgumentException(
                    "projectRelativeRoot must stay relative to the registered project root");
        }
        String portable = path.toString().replace('\\', '/');
        return ".".equals(portable) ? "" : portable;
    }

    private static void requireKnownFormat(String format) {
        if (!FORMAT_V1.equals(format) && !FORMAT_V2.equals(format) && !FORMAT_V3.equals(format)) {
            throw new IllegalArgumentException("unsupported distributed artifact format: " + format);
        }
    }

    private static void requireRootScopeForV1(String format, String scope) {
        if (FORMAT_V1.equals(format) && !scope.isEmpty()) {
            throw new IllegalArgumentException(
                    "format v1 cannot carry a non-root projectRelativeRoot; use FORMAT_V2");
        }
    }
}
