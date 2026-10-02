package com.minos.cli;

import com.minos.application.MinosApplication;
import com.minos.application.MinosApplicationComposer;
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
import com.minos.remote.RemoteRepositoryRequest;
import com.minos.runtime.WorkerSandboxProbe;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Native composition of M25 remote source, worker boundary and verified artifact transport.
 *
 * <p><strong>Closed by decision (ADR 0041).</strong> No integrated sandbox backend is qualified
 * for untrusted remote code on any OS, so {@link #index} refuses <em>before</em> materializing the
 * revision, taking the lease, registering the project or pinning the source — with the per-cause
 * refusal report in the message (the rejected backend and its unmet dimension codes, or the missing
 * prerequisite codes, or the executor without sandbox capability). {@code remote materialize} is
 * unaffected. The transport below stays in place as the contract a future qualified backend must
 * honour; test-only worker factories keep exercising it.</p>
 */
public final class LocalRemoteIndexOperations implements RemoteIndexOperations {

    private final MinosApplication application;
    private final RemoteRepositoryMaterializer materializer;
    /**
     * Never null: every composition goes through the early refusal. Production wires the real host
     * selection (composition root, ADR 0042); a test that wants the transport to reach its worker
     * injects a runtime with a qualified selection explicitly, there is no value that skips the check.
     */
    private final RemoteIndexingRuntime runtime;
    private final Map<String, IndexerDescriptor> descriptors;

    public LocalRemoteIndexOperations(MinosApplication application) throws IOException {
        this(application, application.compositionRoot());
    }

    /** Materializer then runtime, created in this order by the composition root, as before ADR 0042. */
    private LocalRemoteIndexOperations(MinosApplication application, MinosApplicationComposer compositionRoot)
            throws IOException {
        this(application, compositionRoot.remoteRepositoryMaterializer(application.home()),
                compositionRoot.remoteIndexingRuntime(application.home()));
    }

    /** Production runtime (real host selection) with a substituted materializer: tests only. */
    LocalRemoteIndexOperations(MinosApplication application, RemoteRepositoryMaterializer materializer)
            throws IOException {
        this(application, materializer, application.compositionRoot().remoteIndexingRuntime(application.home()));
    }

    LocalRemoteIndexOperations(
            MinosApplication application,
            RemoteRepositoryMaterializer materializer,
            RemoteIndexingRuntime runtime
    ) {
        this.application = Objects.requireNonNull(application, "application");
        this.materializer = IdempotentRemoteRepositoryMaterializer.wrap(
                Objects.requireNonNull(materializer, "materializer"));
        this.runtime = Objects.requireNonNull(runtime, "remoteIndexingRuntime");
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
        refuseUnlessUntrustedCodeSandboxIsQualified();
        RemoteMaterialization source = materializer.materialize(request);
        return indexUnderSourceLease(source, displayName, providerOverride, workerId, workerNetworkPolicy);
    }

    /**
     * Fails closed before any side effect when the host has no sandbox qualified for untrusted
     * remote code. The message carries backend identifiers and dimension codes only, never a path.
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
     * Owns the entire post-materialization lifecycle for {@code source}, including acquiring the
     * cross-JVM {@link RemoteIndexLease}. This method is the single point of responsibility for
     * releasing {@code source} back to {@link #materializer} -- every path out of the try block below
     * (lease acquisition failure, registration failure, pin failure, worker/provider failure, artifact
     * validation failure, commit/promotion failure, or success) runs through the shared cleanup below
     * exactly once, so a successful {@code materialize()} can never leak.
     */
    private RemoteIndexView indexUnderSourceLease(
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
        RemoteIndexView result = null;
        RemoteIndexLease lease = null;
        Exception primaryFailure = null;
        try {
            lease = RemoteIndexLease.acquire(application.home(), source.cacheKey());
            ProjectRegistry.RegistrationResult registration = application.projectRegistry()
                    .registerProjectWithResult(source.projectRoot(), displayName);
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
            AutonomousIndexOperations.IndexExecutionView execution = new LocalAutonomousIndexOperations(
                    application, decorator).execute(project.id().toString(), providerOverride, true);

            List<ArtifactEvidence> evidence = distributedExecutors.stream()
                    .flatMap(executor -> {
                        List<VerifiedArtifactEvidence> verified = executor.verifiedArtifacts();
                        if (verified.isEmpty()) {
                            throw new IllegalStateException(
                                    "distributed provider completed without verified artifact evidence");
                        }
                        return verified.stream();
                    })
                    .map(LocalRemoteIndexOperations::evidence)
                    .sorted(Comparator.comparing(ArtifactEvidence::providerId)
                            .thenComparing(ArtifactEvidence::projectRelativeRoot))
                    .toList();
            result = new RemoteIndexView(
                    view(source), project.id().toString(), project.displayName(), execution, evidence);
            completed = true;
        } catch (Exception exception) {
            primaryFailure = exception;
        }

        Exception cleanupFailure = closeExecutors(distributedExecutors);
        if (!completed && newlyRegistered && project != null) {
            try {
                boolean removed = application.projectRegistry().deleteProject(project.id());
                boolean absent = removed || application.projectRegistry().findProject(project.id()).isEmpty();
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

    private static RemoteMaterializationView view(RemoteMaterialization value) {
        return new RemoteMaterializationView(
                value.request().host().name(), value.request().canonicalRepositoryUri(), value.request().reference(),
                value.request().expectedCommit(), value.repositoryRoot().toString(), value.projectRoot().toString(),
                value.cacheKey(), value.cacheHit(), value.materializedAt().toString());
    }

    private static ArtifactEvidence evidence(VerifiedArtifactEvidence value) {
        var manifest = value.manifest();
        return new ArtifactEvidence(
                manifest.providerId(), manifest.providerVersion(), manifest.language().name(), manifest.workerId(),
                manifest.isolation().name(), manifest.networkPolicy().name(), manifest.networkDenyEnforced(),
                manifest.artifactSha256(), value.bundleSha256(), value.cacheKey(), value.cacheHit(),
                manifest.projectRelativeRoot());
    }
}
