package com.minos.remote;

import static com.minos.domain.Preconditions.requireText;

/**
 * The source a transported artifact was produced from: either a remote revision (formats v1 and v2) or a local
 * project indexed in a WSL2 distribution (format v3). The two never mix: a local source has no revision, and a remote
 * source has no project fingerprint.
 */
public sealed interface DistributedArtifactSource
        permits DistributedArtifactSource.RemoteRevision, DistributedArtifactSource.LocalProject {

    /** {@code REMOTE_GIT} or {@code LOCAL_WSL}, the value written as {@code sourceKind} on the v3 wire. */
    String kind();

    /** A canonical, secret-free repository and a complete lowercase Git SHA-1 commit. */
    record RemoteRevision(String repository, String commit) implements DistributedArtifactSource {

        public static final String SOURCE_KIND = "REMOTE_GIT";

        public RemoteRevision {
            repository = requireText(repository, "sourceRepository");
            if (repository.contains("@") || repository.contains("?") || repository.contains("#")) {
                throw new IllegalArgumentException("sourceRepository must be canonical and secret-free");
            }
            if (commit == null || !commit.matches("[0-9a-f]{40}")) {
                throw new IllegalArgumentException("sourceCommit must be a lowercase complete Git SHA-1");
            }
        }

        @Override
        public String kind() {
            return SOURCE_KIND;
        }
    }

    /** The lowercase SHA-256 fingerprint of a local project, captured by the coordinator before the launch. */
    record LocalProject(String fingerprint) implements DistributedArtifactSource {

        public static final String SOURCE_KIND = "LOCAL_WSL";

        public LocalProject {
            if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("projectFingerprint must be a lowercase SHA-256 value");
            }
        }

        @Override
        public String kind() {
            return SOURCE_KIND;
        }
    }
}
