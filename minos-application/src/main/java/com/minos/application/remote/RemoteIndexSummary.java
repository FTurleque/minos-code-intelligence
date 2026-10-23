package com.minos.application.remote;

import com.minos.application.indexing.IndexExecutionSummary;

import java.util.List;
import java.util.Objects;

import static com.minos.domain.Preconditions.requireText;

/** A successful remote indexing: the materialized source, the registered project, the run and its evidence. */
public record RemoteIndexSummary(
        RemoteMaterializationSummary materialization,
        String projectId,
        String projectName,
        IndexExecutionSummary execution,
        List<ArtifactEvidenceSummary> artifacts
) {
    public RemoteIndexSummary {
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
