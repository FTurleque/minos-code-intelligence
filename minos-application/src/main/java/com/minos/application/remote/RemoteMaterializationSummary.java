package com.minos.application.remote;

import com.minos.remote.RemoteRepositoryMaterializer.RemoteMaterialization;

import static com.minos.domain.Preconditions.requireText;

/** An immutable remote revision materialized in the local cache, as the surfaces show it. */
public record RemoteMaterializationSummary(
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
    public RemoteMaterializationSummary {
        requireText(host, "host");
        requireText(repository, "repository");
        requireText(reference, "reference");
        requireText(commit, "commit");
        requireText(repositoryRoot, "repositoryRoot");
        requireText(projectRoot, "projectRoot");
        requireText(cacheKey, "cacheKey");
        requireText(materializedAt, "materializedAt");
    }

    static RemoteMaterializationSummary of(RemoteMaterialization value) {
        return new RemoteMaterializationSummary(
                value.request().host().name(), value.request().canonicalRepositoryUri(), value.request().reference(),
                value.request().expectedCommit(), value.repositoryRoot().toString(), value.projectRoot().toString(),
                value.cacheKey(), value.cacheHit(), value.materializedAt().toString());
    }
}
