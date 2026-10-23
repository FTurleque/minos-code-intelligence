package com.minos.cli.operations;

import com.minos.application.MinosApplication;
import com.minos.application.remote.ArtifactEvidenceSummary;
import com.minos.application.remote.RemoteIndexSummary;
import com.minos.application.remote.RemoteIndexingUseCase;
import com.minos.application.remote.RemoteMaterializationSummary;
import com.minos.application.remote.RemoteSourceRequest;
import com.minos.application.remote.WorkerNetworkChoice;

import java.io.IOException;
import java.util.Objects;

/**
 * The remote indexing of the command over the application use case {@link RemoteIndexingUseCase} (AUD-ARC-01): it
 * passes the validated source and the options, and projects the summaries into the views of the command.
 *
 * <p><strong>Closed by decision (ADR 0041).</strong> {@code remote index} refuses before any materialization,
 * lease, registration or pin; the refusal is the use case's, unchanged. {@code remote materialize} is
 * unaffected.</p>
 */
public final class LocalRemoteIndexOperations implements RemoteIndexOperations {

    private final RemoteIndexingUseCase remoteIndexing;

    /** Production wiring: materializer and runtime from the composition root (ADR 0042). */
    public LocalRemoteIndexOperations(MinosApplication application) throws IOException {
        this(RemoteIndexingUseCase.of(application));
    }

    /** A use case wired with substituted ports: tests only. */
    LocalRemoteIndexOperations(RemoteIndexingUseCase remoteIndexing) {
        this.remoteIndexing = Objects.requireNonNull(remoteIndexing, "remoteIndexing");
    }

    @Override
    public RemoteMaterializationView materialize(RemoteSourceRequest request) throws Exception {
        return view(remoteIndexing.materialize(request));
    }

    @Override
    public RemoteIndexView index(
            RemoteSourceRequest request,
            String displayName,
            String providerOverride,
            String workerId,
            WorkerNetworkChoice workerNetwork
    ) throws Exception {
        RemoteIndexSummary summary = remoteIndexing.index(request, displayName, providerOverride, workerId, workerNetwork);
        return new RemoteIndexView(view(summary.materialization()), summary.projectId(), summary.projectName(),
                LocalAutonomousIndexOperations.view(summary.execution()),
                summary.artifacts().stream().map(LocalRemoteIndexOperations::view).toList());
    }

    private static RemoteMaterializationView view(RemoteMaterializationSummary value) {
        return new RemoteMaterializationView(value.host(), value.repository(), value.reference(), value.commit(),
                value.repositoryRoot(), value.projectRoot(), value.cacheKey(), value.cacheHit(), value.materializedAt());
    }

    private static ArtifactEvidence view(ArtifactEvidenceSummary value) {
        return new ArtifactEvidence(value.providerId(), value.providerVersion(), value.language(), value.workerId(),
                value.isolation(), value.networkPolicy(), value.networkDenyEnforced(), value.artifactSha256(),
                value.bundleSha256(), value.cacheKey(), value.cacheHit(), value.projectRelativeRoot());
    }
}
