package com.minos.runtime;

import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.remote.DistributedArtifactManifest;
import com.minos.remote.DistributedIndexing.Worker;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import com.minos.remote.DistributedIndexing.WorkerRequest;
import com.minos.remote.DistributedIndexing.WorkerResponse;
import com.minos.remote.RemoteRepositoryMaterializer.RemoteMaterialization;
import com.minos.runtime.DistributedArtifactBundleStore.VerifiedArtifact;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Worker-backed executor that accepts only a verified artifact matching the exact request. */
public final class DistributedIndexerExecutor implements IndexerExecutor, AutoCloseable {

    private final String indexerId;
    private final String providerVersion;
    private final RemoteMaterialization source;
    private final WorkerNetworkPolicy networkPolicy;
    private final Worker worker;
    private final DistributedArtifactBundleStore bundleStore;
    private final List<VerifiedArtifact> leasedArtifacts = new ArrayList<>();
    private volatile VerifiedArtifact lastVerifiedArtifact;

    public DistributedIndexerExecutor(
            String indexerId,
            String providerVersion,
            RemoteMaterialization source,
            WorkerNetworkPolicy networkPolicy,
            Worker worker,
            DistributedArtifactBundleStore bundleStore
    ) {
        this.indexerId = requireText(indexerId, "indexerId");
        this.providerVersion = requireText(providerVersion, "providerVersion");
        this.source = Objects.requireNonNull(source, "source");
        this.networkPolicy = Objects.requireNonNull(networkPolicy, "networkPolicy");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.bundleStore = Objects.requireNonNull(bundleStore, "bundleStore");
    }

    @Override
    public String indexerId() {
        return indexerId;
    }

    @Override
    public IndexingArtifact execute(IndexingExecutionRequest request) throws Exception {
        Objects.requireNonNull(request, "request");
        Path requestedRoot = request.projectRoot().toRealPath();
        if (!requestedRoot.equals(source.projectRoot().toRealPath())) {
            throw new IllegalArgumentException("distributed execution request does not use the materialized project root");
        }
        if (!indexerId.equals(request.selection().indexer().id())) {
            throw new IllegalArgumentException("distributed executor selected another provider");
        }
        WorkerRequest workerRequest = new WorkerRequest(
                request,
                providerVersion,
                source.request().canonicalRepositoryUri(),
                source.request().expectedCommit(),
                networkPolicy
        );
        WorkerResponse response = Objects.requireNonNull(worker.execute(workerRequest), "worker response");
        VerifiedArtifact verified = null;
        boolean retained = false;
        try {
            verified = bundleStore.accept(response.bundle());
            if (!response.manifest().equals(verified.manifest())) {
                throw new IllegalStateException("worker response manifest differs from the transported manifest");
            }
            verifyManifest(request, verified.manifest());
            synchronized (leasedArtifacts) {
                leasedArtifacts.add(verified);
                lastVerifiedArtifact = verified;
            }
            retained = true;
            return new IndexingArtifact(
                    request.selection().language(), indexerId, verified.artifact(), request.projectRelativeRoot());
        } finally {
            if (verified != null && !retained) {
                bundleStore.release(verified);
            }
            Files.deleteIfExists(response.bundle());
        }
    }

    public Optional<VerifiedArtifact> verifiedArtifact() {
        return Optional.ofNullable(lastVerifiedArtifact);
    }

    @Override
    public void close() throws IOException {
        List<VerifiedArtifact> releases;
        synchronized (leasedArtifacts) {
            releases = List.copyOf(leasedArtifacts);
            leasedArtifacts.clear();
            lastVerifiedArtifact = null;
        }
        IOException failure = null;
        for (VerifiedArtifact artifact : releases) {
            try {
                bundleStore.release(artifact);
            } catch (IOException exception) {
                if (failure == null) failure = exception; else failure.addSuppressed(exception);
            }
        }
        if (failure != null) throw failure;
    }

    private void verifyManifest(IndexingExecutionRequest request, DistributedArtifactManifest manifest) {
        if (!request.runId().equals(manifest.runId())
                || !request.projectId().equals(manifest.projectId())
                || !source.request().canonicalRepositoryUri().equals(manifest.sourceRepository())
                || !source.request().expectedCommit().equals(manifest.sourceCommit())
                || request.selection().language() != manifest.language()
                || !indexerId.equals(manifest.providerId())
                || !providerVersion.equals(manifest.providerVersion())
                || !worker.workerId().equals(manifest.workerId())
                || worker.isolation() != manifest.isolation()
                || networkPolicy != manifest.networkPolicy()
                || worker.enforcesNetworkDeny() != manifest.networkDenyEnforced()) {
            throw new IllegalStateException("transported artifact provenance does not match the indexing request");
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}
