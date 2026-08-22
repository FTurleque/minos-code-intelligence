package com.minos.runtime;

import com.minos.orchestration.IndexArtifactLimits;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.orchestration.ProviderId;
import com.minos.remote.DistributedArtifactManifest;
import com.minos.remote.DistributedIndexing.Worker;
import com.minos.remote.DistributedIndexing.WorkerIsolation;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import com.minos.remote.DistributedIndexing.WorkerRequest;
import com.minos.remote.DistributedIndexing.WorkerResponse;
import com.minos.source.SourceBudgetPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Provider worker with copied ephemeral workspace and explicit sandbox backend.
 *
 * <p>The public constructor selects the strongest qualified OS sandbox only when the executor
 * explicitly implements {@link ProcessSandboxCapableIndexerExecutor}. This keeps ownership
 * capability-based instead of concrete-class-based, including wrappers such as
 * {@link StrongProcessOwnershipIndexerExecutor}. Other executors remain fail-closed.</p>
 */
public final class LocalIsolatedIndexWorker implements Worker {

    private static final long DEFAULT_MAX_WORKSPACE_FILES = SourceBudgetPolicy.DEFAULT_MAX_FILES;
    private static final long DEFAULT_MAX_WORKSPACE_BYTES = SourceBudgetPolicy.DEFAULT_MAX_BYTES;
    private static final String WORKSPACE_BOUNDARY = "remote worker workspace";

    private final String workerId;
    private final Path workersRoot;
    private final IndexerExecutor delegate;
    private final DistributedArtifactBundleStore bundleStore;
    private final WorkerSandboxBackend sandboxBackend;
    private final SourceBudgetPolicy sourceBudgetPolicy;
    private final Clock clock;

    public LocalIsolatedIndexWorker(
            String workerId,
            Path minosHome,
            IndexerExecutor delegate,
            DistributedArtifactBundleStore bundleStore
    ) {
        this(
                workerId,
                minosHome,
                delegate,
                bundleStore,
                defaultSandboxBackend(minosHome, delegate),
                DEFAULT_MAX_WORKSPACE_FILES,
                DEFAULT_MAX_WORKSPACE_BYTES,
                Clock.systemUTC());
    }

    LocalIsolatedIndexWorker(
            String workerId,
            Path minosHome,
            IndexerExecutor delegate,
            DistributedArtifactBundleStore bundleStore,
            WorkerSandboxBackend sandboxBackend,
            long maxWorkspaceFiles,
            long maxWorkspaceBytes,
            Clock clock
    ) {
        if (workerId == null || !workerId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("workerId must be a safe non-blank identifier");
        }
        this.workerId = workerId;
        this.workersRoot = Objects.requireNonNull(minosHome, "minosHome")
                .toAbsolutePath().normalize().resolve("distributed-workers");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        ProviderId.require(this.delegate.indexerId());
        this.bundleStore = Objects.requireNonNull(bundleStore, "bundleStore");
        this.sandboxBackend = Objects.requireNonNull(sandboxBackend, "sandboxBackend");
        if (sandboxBackend.id() == null || sandboxBackend.id().isBlank()) {
            throw new IllegalArgumentException("sandbox backend id must not be blank");
        }
        Objects.requireNonNull(sandboxBackend.isolation(), "sandbox backend isolation");
        Objects.requireNonNull(sandboxBackend.networkGuarantee(), "sandbox backend network guarantee");
        this.sourceBudgetPolicy = new SourceBudgetPolicy(maxWorkspaceFiles, maxWorkspaceBytes);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String workerId() {
        return workerId;
    }

    public String sandboxBackendId() {
        return sandboxBackend.id();
    }

    @Override
    public WorkerIsolation isolation() {
        return sandboxBackend.isolation();
    }

    @Override
    public boolean enforcesNetworkDeny() {
        return sandboxBackend.enforcesNetworkDeny();
    }

    @Override
    public WorkerResponse execute(WorkerRequest request) throws Exception {
        Objects.requireNonNull(request, "request");
        if (!delegate.indexerId().equals(request.execution().selection().indexer().id())) {
            throw new IllegalArgumentException("worker delegate does not match selected provider");
        }
        if (!sandboxBackend.supportsUntrustedCode()) {
            throw new IllegalStateException(
                    "sandbox backend " + sandboxBackend.id()
                            + " is not qualified for untrusted remote code on the current platform; execution is fail-closed");
        }
        if (request.networkPolicy() == WorkerNetworkPolicy.DENY
                && !sandboxBackend.enforcesNetworkDeny()) {
            throw new IllegalStateException(
                    "sandbox backend " + sandboxBackend.id()
                            + " cannot prove OS-level network denial; DENY remains fail-closed");
        }

        Files.createDirectories(workersRoot);
        Path providerRoot = workersRoot
                .resolve(request.execution().runId().toString())
                .resolve(ProviderId.require(delegate.indexerId()))
                .toAbsolutePath().normalize();
        if (!providerRoot.startsWith(workersRoot)) {
            throw new IOException("worker provider path escapes distributed worker root");
        }
        Path workspace = providerRoot.resolve("workspace");
        Files.createDirectories(providerRoot);
        if (Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) {
            ProviderWorkspaceFiles.deleteTree(workersRoot, workspace, WORKSPACE_BOUNDARY);
        }
        Files.createDirectory(workspace);

        String portableScope = portableScope(request.execution().projectRelativeRoot());
        Instant startedAt = clock.instant();
        try {
            ProviderWorkspaceFiles.copyWorkspace(
                    request.execution().projectRoot(), workspace, sourceBudgetPolicy, WORKSPACE_BOUNDARY);
            IndexingExecutionRequest isolated = new IndexingExecutionRequest(
                    request.execution().runId(),
                    request.execution().projectId(),
                    workspace,
                    request.execution().selection(),
                    request.execution().mode(),
                    request.execution().changedFiles());
            IndexingArtifact artifact = Objects.requireNonNull(
                    sandboxBackend.execute(delegate, isolated, request.networkPolicy()),
                    "worker sandbox artifact");
            if (artifact.language() != isolated.selection().language()
                    || !artifact.indexerId().equals(delegate.indexerId())) {
                throw new IOException(
                        "worker sandbox returned artifact provenance for another provider/language");
            }
            Path artifactPath = artifact.finalArtifact().toAbsolutePath().normalize();
            if (Files.isSymbolicLink(artifactPath)
                    || !Files.isRegularFile(artifactPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("worker sandbox did not produce a non-empty regular artifact");
            }
            long artifactBytes = Files.size(artifactPath);
            if (artifactBytes < 1L) throw new IOException("worker sandbox did not produce a non-empty regular artifact");
            if (artifactBytes > IndexArtifactLimits.MAX_SCIP_ARTIFACT_BYTES) {
                throw new IOException("worker SCIP artifact exceeds configured byte limit before hashing");
            }
            Instant completedAt = clock.instant();
            DistributedArtifactManifest manifest = new DistributedArtifactManifest(
                    DistributedArtifactManifest.FORMAT_V2,
                    isolated.runId(),
                    isolated.projectId(),
                    portableScope,
                    request.sourceRepository(),
                    request.sourceCommit(),
                    artifact.language(),
                    artifact.indexerId(),
                    request.providerVersion(),
                    workerId,
                    isolation(),
                    request.networkPolicy(),
                    request.networkPolicy() == WorkerNetworkPolicy.DENY
                            && sandboxBackend.enforcesNetworkDeny(),
                    startedAt,
                    completedAt,
                    DistributedArtifactManifest.ARTIFACT_PATH,
                    artifactBytes,
                    DistributedArtifactBundleStore.sha256(artifactPath));
            Path bundle = Files.createTempFile(workersRoot, ".bundle-", ".zip");
            try {
                bundleStore.createBundle(bundle, manifest, artifactPath);
                return new WorkerResponse(bundle, manifest);
            } catch (Exception exception) {
                Files.deleteIfExists(bundle);
                throw exception;
            }
        } finally {
            if (Files.exists(providerRoot, LinkOption.NOFOLLOW_LINKS)) {
                ProviderWorkspaceFiles.deleteTree(workersRoot, providerRoot, WORKSPACE_BOUNDARY);
            }
            try {
                Files.deleteIfExists(providerRoot.getParent());
            } catch (java.nio.file.DirectoryNotEmptyException ignored) {
                // Another provider from the same run still owns its isolated directory.
            }
        }
    }

    private static WorkerSandboxBackend defaultSandboxBackend(Path minosHome, IndexerExecutor delegate) {
        Objects.requireNonNull(minosHome, "minosHome");
        Objects.requireNonNull(delegate, "delegate");
        return delegate instanceof ProcessSandboxCapableIndexerExecutor
                ? WorkerSandboxBackends.strongestAvailable(minosHome)
                : WorkerSandboxBackend.nativeEphemeralWorkspace();
    }

    private static String portableScope(Path projectRelativeRoot) {
        String portable = projectRelativeRoot.toString().replace('\\', '/');
        return ".".equals(portable) ? "" : portable;
    }
}
