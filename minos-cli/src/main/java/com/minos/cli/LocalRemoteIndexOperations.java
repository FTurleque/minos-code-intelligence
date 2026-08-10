package com.minos.cli;

import com.minos.application.MinosApplication;
import com.minos.git.JGitRemoteRepositoryMaterializer;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.registry.RegisteredProject;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import com.minos.remote.RemoteRepositoryMaterializer;
import com.minos.remote.RemoteRepositoryMaterializer.RemoteMaterialization;
import com.minos.remote.RemoteRepositoryRequest;
import com.minos.runtime.DistributedArtifactBundleStore;
import com.minos.runtime.DistributedArtifactBundleStore.VerifiedArtifact;
import com.minos.runtime.DistributedIndexerExecutor;
import com.minos.runtime.LocalIsolatedIndexWorker;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/** Native composition of M25 remote source, worker boundary and verified artifact transport. */
public final class LocalRemoteIndexOperations implements RemoteIndexOperations {

    private final MinosApplication application;
    private final RemoteRepositoryMaterializer materializer;
    private final DistributedArtifactBundleStore artifactStore;
    private final Map<String, IndexerDescriptor> descriptors;

    public LocalRemoteIndexOperations(MinosApplication application) throws IOException {
        this(application, new JGitRemoteRepositoryMaterializer(application.home()),
                new DistributedArtifactBundleStore(application.home()));
    }

    LocalRemoteIndexOperations(
            MinosApplication application,
            RemoteRepositoryMaterializer materializer,
            DistributedArtifactBundleStore artifactStore
    ) {
        this.application = Objects.requireNonNull(application, "application");
        this.materializer = Objects.requireNonNull(materializer, "materializer");
        this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
        this.descriptors = application.indexerDescriptors().stream().collect(Collectors.toUnmodifiableMap(
                IndexerDescriptor::id, descriptor -> descriptor));
    }

    @Override
    public RemoteMaterializationView materialize(RemoteRepositoryRequest request) throws Exception {
        RemoteMaterialization source = materializer.materialize(request);
        try {
            return view(source);
        } finally {
            materializer.release(source);
        }
    }

    @Override
    public RemoteIndexView index(
            RemoteRepositoryRequest request,
            String displayName,
            String providerOverride,
            String workerId,
            WorkerNetworkPolicy workerNetworkPolicy
    ) throws Exception {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        RemoteMaterialization source = materializer.materialize(request);
        try {
            RegisteredProject project = application.projectRegistry().registerProject(source.projectRoot(), displayName);
            // The registry persists rootPath beyond this index invocation. Pin the cache entry before
            // releasing its active lease so later LRU eviction cannot orphan that durable project.
            materializer.pin(source);
            List<DistributedIndexerExecutor> distributedExecutors = new ArrayList<>();
            UnaryOperator<IndexerExecutor> decorator = delegate -> {
                IndexerDescriptor descriptor = descriptors.get(delegate.indexerId());
                if (descriptor == null) {
                    throw new IllegalStateException("remote execution has no descriptor for provider: " + delegate.indexerId());
                }
                LocalIsolatedIndexWorker worker = new LocalIsolatedIndexWorker(
                        workerId, application.home(), delegate, artifactStore);
                DistributedIndexerExecutor distributed = new DistributedIndexerExecutor(
                        descriptor.id(), descriptor.version(), source, workerNetworkPolicy, worker, artifactStore);
                distributedExecutors.add(distributed);
                return distributed;
            };
            AutonomousIndexOperations.IndexExecutionView execution = new LocalAutonomousIndexOperations(
                    application, decorator).execute(project.id().toString(), providerOverride, true);

            List<ArtifactEvidence> evidence = distributedExecutors.stream()
                    .map(executor -> executor.verifiedArtifact().orElseThrow(() ->
                            new IllegalStateException("distributed provider completed without verified artifact evidence")))
                    .map(LocalRemoteIndexOperations::evidence)
                    .sorted(java.util.Comparator.comparing(ArtifactEvidence::providerId))
                    .toList();
            return new RemoteIndexView(view(source), project.id().toString(), project.displayName(), execution, evidence);
        } finally {
            materializer.release(source);
        }
    }

    private static RemoteMaterializationView view(RemoteMaterialization value) {
        return new RemoteMaterializationView(
                value.request().host().name(), value.request().canonicalRepositoryUri(), value.request().reference(),
                value.request().expectedCommit(), value.repositoryRoot().toString(), value.projectRoot().toString(),
                value.cacheKey(), value.cacheHit(), value.materializedAt().toString());
    }

    private static ArtifactEvidence evidence(VerifiedArtifact value) {
        var manifest = value.manifest();
        return new ArtifactEvidence(
                manifest.providerId(), manifest.providerVersion(), manifest.language().name(), manifest.workerId(),
                manifest.isolation().name(), manifest.networkPolicy().name(), manifest.networkDenyEnforced(),
                manifest.artifactSha256(), value.bundleSha256(), value.cacheKey(), value.cacheHit());
    }
}
