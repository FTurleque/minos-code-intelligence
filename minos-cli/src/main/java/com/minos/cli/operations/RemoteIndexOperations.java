package com.minos.cli.operations;

import com.minos.application.remote.ArtifactEvidenceSummary;
import com.minos.application.remote.RemoteMaterializationSummary;
import com.minos.application.remote.RemoteSourceRequest;
import com.minos.application.remote.WorkerNetworkChoice;

import java.util.List;
import java.util.Objects;

import static com.minos.domain.Preconditions.requireText;

/** CLI port for opt-in immutable remote materialization and worker-backed indexing. */
public interface RemoteIndexOperations {

    RemoteMaterializationSummary materialize(RemoteSourceRequest request) throws Exception;

    RemoteIndexView index(
            RemoteSourceRequest request,
            String displayName,
            String providerOverride,
            String workerId,
            WorkerNetworkChoice workerNetwork
    ) throws Exception;

    /**
     * The remote indexing as the command shows it. The materialization and the artifact evidence are the
     * application summaries themselves; the execution is the CLI view, which sanitizes its diagnostic.
     * {@code CliViewParityTest} keeps this view exhaustive against {@code RemoteIndexSummary}.
     */
    record RemoteIndexView(
            RemoteMaterializationSummary materialization,
            String projectId,
            String projectName,
            AutonomousIndexOperations.IndexExecutionView execution,
            List<ArtifactEvidenceSummary> artifacts
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
}
