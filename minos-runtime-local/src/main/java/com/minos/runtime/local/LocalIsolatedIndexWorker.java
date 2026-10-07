package com.minos.runtime.local;

import com.minos.io.PrivateLocalStorage;
import com.minos.io.StaleScratchReclamation;
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
 *
 * <p><strong>Closed by decision (ADR 0041).</strong> No integrated backend is qualified for
 * untrusted remote code on any OS, so {@link #execute} refuses before copying a workspace or
 * launching a provider. The refusal names the rejected OS backend and its exact unmet dimension
 * codes ({@link WorkerSandboxSelection#refusalReport()}); the transport below it stays in place
 * as the contract a future qualified backend must honour.</p>
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
    private final WorkerSandboxSelection sandboxSelection;
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
                defaultSandboxSelection(minosHome, delegate),
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
        this(workerId, minosHome, delegate, bundleStore,
                WorkerSandboxSelection.of(Objects.requireNonNull(sandboxBackend, "sandboxBackend")),
                maxWorkspaceFiles, maxWorkspaceBytes, clock);
    }

    LocalIsolatedIndexWorker(
            String workerId,
            Path minosHome,
            IndexerExecutor delegate,
            DistributedArtifactBundleStore bundleStore,
            WorkerSandboxSelection sandboxSelection,
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
        this.sandboxSelection = Objects.requireNonNull(sandboxSelection, "sandboxSelection");
        this.sandboxBackend = sandboxSelection.backend();
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

    /** Run directories (named by a run identifier) and unconsumed hand-off bundles are the residues of a run. */
    private static boolean isWorkerScratchName(String name) {
        if (name.startsWith(".bundle-") && name.endsWith(".zip")) return true;
        try {
            return java.util.UUID.fromString(name).toString().equalsIgnoreCase(name);
        } catch (IllegalArgumentException notARunIdentifier) {
            return false;
        }
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
                            + " is not qualified for untrusted remote code on the current platform; "
                            + sandboxSelection.refusalReport());
        }
        if (request.networkPolicy() == WorkerNetworkPolicy.DENY
                && !sandboxBackend.enforcesNetworkDeny()) {
            throw new IllegalStateException(
                    "sandbox backend " + sandboxBackend.id()
                            + " cannot prove OS-level network denial; DENY remains fail-closed");
        }

        PrivateLocalStorage.ensurePrivateDirectory(workersRoot);
        // MINOS-AUD-A02 (dormant code, ADR 0041): the directories and the hand-off bundles of a killed run are never
        // removed by it. Reclaimed where the next run starts, bounded and never fatal; the current run is protected.
        StaleScratchReclamation.reclaim(workersRoot, LocalIsolatedIndexWorker::isWorkerScratchName,
                java.util.Set.of(workersRoot.resolve(request.execution().runId().toString())), clock.instant());
        Path providerRoot = workersRoot
                .resolve(request.execution().runId().toString())
                .resolve(ProviderId.require(delegate.indexerId()))
                .toAbsolutePath().normalize();
        if (!providerRoot.startsWith(workersRoot)) {
            throw new IOException("worker provider path escapes distributed worker root");
        }
        Path workspace = providerRoot.resolve("workspace");
        PrivateLocalStorage.ensurePrivateDirectory(providerRoot);
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
            Path bundle = PrivateLocalStorage.createPrivateTempFile(workersRoot, ".bundle-", ".zip");
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

    private static WorkerSandboxSelection defaultSandboxSelection(Path minosHome, IndexerExecutor delegate) {
        Objects.requireNonNull(minosHome, "minosHome");
        Objects.requireNonNull(delegate, "delegate");
        return delegate instanceof ProcessSandboxCapableIndexerExecutor
                ? WorkerSandboxBackends.selectForUntrustedCode(minosHome)
                : WorkerSandboxSelection.executorNotSandboxCapable();
    }

    private static String portableScope(Path projectRelativeRoot) {
        String portable = projectRelativeRoot.toString().replace('\\', '/');
        return ".".equals(portable) ? "" : portable;
    }
}
