package com.minos.cli;

import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import com.minos.remote.RemoteRepositoryRequest;

import java.util.List;
import java.util.Objects;

/** CLI port for opt-in immutable remote materialization and worker-backed indexing. */
public interface RemoteIndexOperations {

    RemoteMaterializationView materialize(RemoteRepositoryRequest request) throws Exception;

    RemoteIndexView index(
            RemoteRepositoryRequest request,
            String displayName,
            String providerOverride,
            String workerId,
            WorkerNetworkPolicy workerNetworkPolicy
    ) throws Exception;

    record RemoteMaterializationView(
            String host,
            String repository,
            String reference,
            String commit,
            String repositoryRoot,
            String projectRoot,
            String cacheKey,
            boolean cacheHit,
            String materializedAt
    ) {
        public RemoteMaterializationView {
            requireText(host, "host");
            requireText(repository, "repository");
            requireText(reference, "reference");
            requireText(commit, "commit");
            requireText(repositoryRoot, "repositoryRoot");
            requireText(projectRoot, "projectRoot");
            requireText(cacheKey, "cacheKey");
            requireText(materializedAt, "materializedAt");
        }
    }

    record ArtifactEvidence(
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
        public ArtifactEvidence {
            requireText(providerId, "providerId");
            requireText(providerVersion, "providerVersion");
            requireText(language, "language");
            requireText(workerId, "workerId");
            requireText(isolation, "isolation");
            requireText(networkPolicy, "networkPolicy");
            requireText(artifactSha256, "artifactSha256");
            requireText(bundleSha256, "bundleSha256");
            requireText(cacheKey, "cacheKey");
            // projectRelativeRoot is "" for root scope — requireNonNull, not requireText
            Objects.requireNonNull(projectRelativeRoot, "projectRelativeRoot");
        }
    }

    record RemoteIndexView(
            RemoteMaterializationView materialization,
            String projectId,
            String projectName,
            AutonomousIndexOperations.IndexExecutionView execution,
            List<ArtifactEvidence> artifacts
    ) {
        public RemoteIndexView {
            Objects.requireNonNull(materialization, "materialization");
            requireText(projectId, "projectId");
            requireText(projectName, "projectName");
            Objects.requireNonNull(execution, "execution");
            artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
            if (artifacts.isEmpty()) {
                throw new IllegalArgumentException("remote indexing must expose artifact evidence");
            }
        }
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
    }
}
