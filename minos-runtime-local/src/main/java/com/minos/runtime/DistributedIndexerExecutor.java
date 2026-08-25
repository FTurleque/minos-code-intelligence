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
        verifyMaterializedScope(request);
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
            Path manifestScope = verified.manifest().projectRelativeRoot().isEmpty()
                    ? Path.of("")
                    : Path.of(verified.manifest().projectRelativeRoot());
            return new IndexingArtifact(
                    request.selection().language(), indexerId, verified.artifact(), manifestScope);
        } finally {
            if (verified != null && !retained) {
                bundleStore.release(verified);
            }
            Files.deleteIfExists(response.bundle());
        }
    }

    /**
     * Returns every verified artifact retained by this provider executor, in execution order.
     * A provider can execute once per module/build root in a monorepo, so callers must not
     * collapse provenance to the most recently completed scope.
     */
    public List<VerifiedArtifact> verifiedArtifacts() {
        synchronized (leasedArtifacts) {
            return List.copyOf(leasedArtifacts);
        }
    }

    /** Compatibility accessor for single-scope callers. */
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

    private void verifyMaterializedScope(IndexingExecutionRequest request) throws IOException {
        Path repositoryRoot = source.repositoryRoot().toRealPath();
        Path materializedRoot = source.projectRoot().toRealPath();
        Path registeredRoot = request.registeredProjectRoot().toRealPath();
        Path requestedRoot = request.projectRoot().toRealPath();

        if (!materializedRoot.startsWith(repositoryRoot)
                || !registeredRoot.startsWith(repositoryRoot)
                || !requestedRoot.startsWith(registeredRoot)) {
            throw new IllegalArgumentException("distributed execution scope escapes the materialized repository");
        }

        Path expectedRoot = request.projectRelativeRoot().toString().isEmpty()
                ? registeredRoot
                : registeredRoot.resolve(request.projectRelativeRoot()).normalize();
        if (!expectedRoot.equals(requestedRoot)) {
            throw new IllegalArgumentException(
                    "distributed execution scope does not match projectRelativeRoot");
        }

        if (!materializedRoot.equals(registeredRoot) && !materializedRoot.equals(requestedRoot)) {
            throw new IllegalArgumentException(
                    "distributed execution request is not bound to the materialized project root or exact scope");
        }
    }

    private void verifyManifest(IndexingExecutionRequest request, DistributedArtifactManifest manifest) {
        // FORMAT_V1 did not carry projectRelativeRoot on the wire. Treating its absence as root
        // would manufacture provenance, so verified distributed execution is V2-or-newer only.
        if (!DistributedArtifactManifest.FORMAT_V2.equals(manifest.format())) {
            throw new IllegalStateException(
                    "verified distributed artifact provenance requires scope-aware manifest format v2");
        }
        if (!request.runId().equals(manifest.runId())
                || !request.projectId().equals(manifest.projectId())
                || !portableScope(request.projectRelativeRoot()).equals(manifest.projectRelativeRoot())
                || !source.request().canonicalRepositoryUri().equals(manifest.sourceRepository())
                || !source.request().expectedCommit().equals(manifest.sourceCommit())
                || request.selection().language() != manifest.language()
                || !indexerId.equals(manifest.providerId())
                || !providerVersion.equals(manifest.providerVersion())
                || !worker.workerId().equals(manifest.workerId())
                || worker.isolation() != manifest.isolation()
                || networkPolicy != manifest.networkPolicy()
                || (networkPolicy == WorkerNetworkPolicy.DENY && worker.enforcesNetworkDeny())
                        != manifest.networkDenyEnforced()) {
            throw new IllegalStateException("transported artifact provenance does not match the indexing request");
        }
    }

    private static String portableScope(Path projectRelativeRoot) {
        String portable = projectRelativeRoot.toString().replace('\\', '/');
        return ".".equals(portable) ? "" : portable;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}
