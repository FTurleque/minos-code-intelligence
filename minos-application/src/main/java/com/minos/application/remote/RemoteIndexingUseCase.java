package com.minos.application.remote;

import com.minos.application.MinosApplication;
import com.minos.application.MinosApplicationComposer;
import com.minos.application.indexing.IndexExecutionSummary;
import com.minos.application.indexing.IndexingResumeChoice;
import com.minos.application.indexing.ProjectIndexingUseCase;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import com.minos.remote.IdempotentRemoteRepositoryMaterializer;
import com.minos.remote.RemoteIndexingRuntime;
import com.minos.remote.RemoteIndexingRuntime.DistributedExecution;
import com.minos.remote.RemoteIndexingRuntime.VerifiedArtifactEvidence;
import com.minos.remote.RemoteRepositoryMaterializer;
import com.minos.remote.RemoteRepositoryMaterializer.RemoteMaterialization;
import com.minos.runtime.WorkerSandboxProbe;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * The remote indexing of the product (AUD-ARC-01, ADR 0057 sec. 6): immutable materialization of a remote revision,
 * cross-JVM lease, registration, pin, distributed execution through the local indexing use case, verified artifact
 * evidence and rollback. Invocable without any class of a surface; a surface converts its options and projects the
 * summaries.
 *
 * <p><strong>Closed by decision (ADR 0041).</strong> No integrated sandbox backend is qualified for untrusted remote
 * code on any OS, so {@link #index} refuses <em>before</em> materializing the revision, taking the lease, registering
 * the project or pinning the source, with the per-cause refusal report in the message (the rejected backend and its
 * unmet dimension codes, or the missing prerequisite codes, or the executor without sandbox capability).
 * {@link #materialize} is unaffected. The transport below stays in place as the contract a future qualified backend
 * must honour; test-only worker factories keep exercising it.</p>
 */
public final class RemoteIndexingUseCase {

    private final Path home;
    private final ProjectRegistry projectRegistry;
    private final RemoteRepositoryMaterializer materializer;
    /**
     * Never null: every composition goes through the early refusal. Production wires the real host selection
     * (composition root, ADR 0042); a test that wants the transport to reach its worker injects a runtime with a
     * qualified selection explicitly, there is no value that skips the check.
     */
    private final RemoteIndexingRuntime runtime;
    private final Map<String, IndexerDescriptor> descriptors;
    private final LocalIndexing indexing;

    /** The local indexing of a registered project, with every provider executor wrapped by {@code decorator}. */
    @FunctionalInterface
    interface LocalIndexing {
        IndexExecutionSummary execute(String projectId, String providerOverride,
                                      UnaryOperator<IndexerExecutor> decorator) throws IOException;
    }

    /** The ports of the remote indexing; production takes them from {@link MinosApplication}. */
    record Dependencies(
            Path home,
            ProjectRegistry projectRegistry,
            RemoteRepositoryMaterializer materializer,
            RemoteIndexingRuntime runtime,
            List<IndexerDescriptor> descriptors,
            LocalIndexing indexing
    ) {
        Dependencies {
            home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
            Objects.requireNonNull(projectRegistry, "projectRegistry");
            Objects.requireNonNull(materializer, "materializer");
            Objects.requireNonNull(runtime, "remoteIndexingRuntime");
            descriptors = List.copyOf(Objects.requireNonNull(descriptors, "descriptors"));
            Objects.requireNonNull(indexing, "indexing");
        }
    }

    RemoteIndexingUseCase(Dependencies dependencies) {
        Objects.requireNonNull(dependencies, "dependencies");
        this.home = dependencies.home();
        this.projectRegistry = dependencies.projectRegistry();
        this.materializer = IdempotentRemoteRepositoryMaterializer.wrap(dependencies.materializer());
        this.runtime = dependencies.runtime();
        this.descriptors = dependencies.descriptors().stream().collect(Collectors.toUnmodifiableMap(
                IndexerDescriptor::id, descriptor -> descriptor));
        this.indexing = dependencies.indexing();
    }

    /**
     * The production wiring: the materializer then the runtime, created in this order by the composition root
     * (ADR 0042); the local indexing is {@link ProjectIndexingUseCase} of the same application.
     */
    public static RemoteIndexingUseCase of(MinosApplication application) throws IOException {
        Objects.requireNonNull(application, "application");
        MinosApplicationComposer compositionRoot = application.compositionRoot();
        RemoteRepositoryMaterializer materializer = compositionRoot.remoteRepositoryMaterializer(application.home());
        return of(application, materializer, compositionRoot.remoteIndexingRuntime(application.home()));
    }

    /** The wiring of {@code application} with an explicit materializer and runtime (substituted by tests). */
    public static RemoteIndexingUseCase of(
            MinosApplication application,
            RemoteRepositoryMaterializer materializer,
            RemoteIndexingRuntime runtime
    ) {
        Objects.requireNonNull(application, "application");
        return new RemoteIndexingUseCase(new Dependencies(application.home(), application.projectRegistry(),
                materializer, runtime, application.indexerDescriptors(),
                (projectId, providerOverride, decorator) -> ProjectIndexingUseCase.of(application, decorator)
                        .executeSummary(projectId, providerOverride, true, IndexingResumeChoice.RESUME)));
    }

    /** Materializes the revision, describes it and releases it: nothing stays referenced by this call. */
    public RemoteMaterializationSummary materialize(RemoteSourceRequest request) throws Exception {
        RemoteMaterialization source = materializer.materialize(Objects.requireNonNull(request, "request").request());
        try {
            return RemoteMaterializationSummary.of(source);
        } finally {
            materializer.release(source);
        }
    }

    /** Indexes the revision through a worker, under the refusal of ADR 0041 (see the class description). */
    public RemoteIndexSummary index(
            RemoteSourceRequest request,
            String displayName,
            String providerOverride,
            String workerId,
            WorkerNetworkChoice workerNetwork
    ) throws Exception {
        Objects.requireNonNull(request, "request");
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        WorkerNetworkPolicy workerNetworkPolicy = Objects.requireNonNull(workerNetwork, "workerNetwork").policy();
        refuseUnlessUntrustedCodeSandboxIsQualified();
        RemoteMaterialization source = materializer.materialize(request.request());
        return indexUnderSourceLease(source, displayName, providerOverride, workerId, workerNetworkPolicy);
    }

    /**
     * Fails closed before any side effect when the host has no sandbox qualified for untrusted remote code. The
     * message carries backend identifiers and dimension codes only, never a path.
     */
    private void refuseUnlessUntrustedCodeSandboxIsQualified() {
        WorkerSandboxProbe.UntrustedCodeSandbox selection = Objects.requireNonNull(
                runtime.untrustedCodeSandbox(), "untrusted-code sandbox selection");
        if (selection.supportsUntrustedCode()) return;
        throw new IllegalStateException(
                "remote index is refused before any materialization: sandbox backend "
                        + selection.backendId()
                        + " is not qualified for untrusted remote code on the current platform; "
                        + selection.refusalReport());
    }

    /**
     * Owns the entire post-materialization lifecycle for {@code source}, including acquiring the cross-JVM
     * {@link RemoteIndexLease}. This method is the single point of responsibility for releasing {@code source} back
     * to {@link #materializer}: every path out of the try block below (lease acquisition failure, registration
     * failure, pin failure, worker/provider failure, artifact validation failure, commit/promotion failure, or
     * success) runs through the shared cleanup below exactly once, so a successful {@code materialize()} can never
     * leak.
     */
    private RemoteIndexSummary indexUnderSourceLease(
            RemoteMaterialization source,
            String displayName,
            String providerOverride,
            String workerId,
            WorkerNetworkPolicy workerNetworkPolicy
    ) throws Exception {
        List<DistributedExecution> distributedExecutors = new ArrayList<>();
        RegisteredProject project = null;
        boolean newlyRegistered = false;
        boolean pinned = false;
        boolean completed = false;
        RemoteIndexSummary result = null;
        RemoteIndexLease lease = null;
        Exception primaryFailure = null;
        try {
            lease = RemoteIndexLease.acquire(home, source.cacheKey());
            ProjectRegistry.RegistrationResult registration =
                    projectRegistry.registerProjectWithResult(source.projectRoot(), displayName);
            project = registration.project();
            newlyRegistered = registration.createdByThisCall();
            materializer.pin(source);
            pinned = true;

            UnaryOperator<IndexerExecutor> decorator = delegate -> {
                IndexerDescriptor descriptor = descriptors.get(delegate.indexerId());
                if (descriptor == null) {
                    throw new IllegalStateException("remote execution has no descriptor for provider: " + delegate.indexerId());
                }
                DistributedExecution distributed = Objects.requireNonNull(runtime.distribute(
                        descriptor.id(), descriptor.version(), source, workerNetworkPolicy, workerId, delegate),
                        "distributed execution");
                distributedExecutors.add(distributed);
                return distributed;
            };
            // The run is always complete (forced): a remote revision is indexed as a whole.
            IndexExecutionSummary execution = indexing.execute(project.id().toString(), providerOverride, decorator);

            List<ArtifactEvidenceSummary> evidence = distributedExecutors.stream()
                    .flatMap(executor -> {
                        List<VerifiedArtifactEvidence> verified = executor.verifiedArtifacts();
                        if (verified.isEmpty()) {
                            throw new IllegalStateException(
                                    "distributed provider completed without verified artifact evidence");
                        }
                        return verified.stream();
                    })
                    .map(ArtifactEvidenceSummary::of)
                    .sorted(Comparator.comparing(ArtifactEvidenceSummary::providerId)
                            .thenComparing(ArtifactEvidenceSummary::projectRelativeRoot))
                    .toList();
            result = new RemoteIndexSummary(RemoteMaterializationSummary.of(source), project.id().toString(),
                    project.displayName(), execution, evidence);
            completed = true;
        } catch (Exception exception) {
            primaryFailure = exception;
        }

        Exception cleanupFailure = closeExecutors(distributedExecutors);
        if (!completed && newlyRegistered && project != null) {
            try {
                boolean removed = projectRegistry.deleteProject(project.id());
                boolean absent = removed || projectRegistry.findProject(project.id()).isEmpty();
                if (absent && pinned) {
                    materializer.unpin(source);
                }
            } catch (Exception exception) {
                cleanupFailure = combine(cleanupFailure, exception);
            }
        }
        try {
            materializer.release(source);
        } catch (Exception exception) {
            cleanupFailure = combine(cleanupFailure, exception);
        }
        if (lease != null) {
            try {
                lease.close();
            } catch (Exception exception) {
                cleanupFailure = combine(cleanupFailure, exception);
            }
        }

        if (primaryFailure != null) {
            if (cleanupFailure != null) primaryFailure.addSuppressed(cleanupFailure);
            throw primaryFailure;
        }
        if (cleanupFailure != null) throw cleanupFailure;
        return Objects.requireNonNull(result, "remote index result");
    }

    private static Exception closeExecutors(List<DistributedExecution> executors) {
        Exception failure = null;
        for (DistributedExecution executor : executors) {
            try {
                executor.close();
            } catch (Exception exception) {
                failure = combine(failure, exception);
            }
        }
        return failure;
    }

    private static Exception combine(Exception first, Exception next) {
        if (first == null) return next;
        first.addSuppressed(next);
        return first;
    }
}
