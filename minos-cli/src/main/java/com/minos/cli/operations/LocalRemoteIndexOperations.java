package com.minos.cli.operations;

import com.minos.application.MinosApplication;
import com.minos.application.remote.RemoteIndexSummary;
import com.minos.application.remote.RemoteIndexingUseCase;
import com.minos.application.remote.RemoteMaterializationSummary;
import com.minos.application.remote.RemoteSourceRequest;
import com.minos.application.remote.WorkerNetworkChoice;

import java.io.IOException;
import java.util.Objects;

/**
 * The remote indexing of the command over the application use case {@link RemoteIndexingUseCase} (AUD-ARC-01): it
 * passes the validated source and the options, and renders the application summaries, the execution through the view of the command.
 *
 * <p><strong>Closed by decision (ADR 0041).</strong> {@code remote index} refuses before any materialization,
 * lease, registration or pin; the refusal is the use case's, unchanged. {@code remote materialize} is
 * unaffected.</p>
 */
public final class LocalRemoteIndexOperations implements RemoteIndexOperations {

    private final RemoteIndexingUseCase remoteIndexing;

    /** Production wiring: materializer and runtime from the composition root (ADR 0042). */
    public LocalRemoteIndexOperations(MinosApplication application) throws IOException {
        this(application.remoteIndexing());
    }

    /** A use case wired with substituted ports: tests only. */
    LocalRemoteIndexOperations(RemoteIndexingUseCase remoteIndexing) {
        this.remoteIndexing = Objects.requireNonNull(remoteIndexing, "remoteIndexing");
    }

    @Override
    public RemoteMaterializationSummary materialize(RemoteSourceRequest request) throws Exception {
        return remoteIndexing.materialize(request);
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
        return new RemoteIndexView(summary.materialization(), summary.projectId(), summary.projectName(),
                LocalAutonomousIndexOperations.view(summary.execution()), summary.artifacts());
    }
}
