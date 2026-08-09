package com.minos.runtime;

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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.DosFileAttributeView;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;

/**
 * Provider worker with copied ephemeral workspace and explicit sandbox backend.
 *
 * <p>The default backend is the strongest qualified implementation available on the current OS.
 * If no qualified OS sandbox is available, MINOS falls back to the process-only backend and DENY
 * remains fail-closed rather than pretending that network isolation was enforced.</p>
 */
public final class LocalIsolatedIndexWorker implements Worker {

    private static final long DEFAULT_MAX_WORKSPACE_FILES = 100_000L;
    private static final long DEFAULT_MAX_WORKSPACE_BYTES = 2L * 1024L * 1024L * 1024L;

    private final String workerId;
    private final Path workersRoot;
    private final IndexerExecutor delegate;
    private final DistributedArtifactBundleStore bundleStore;
    private final WorkerSandboxBackend sandboxBackend;
    private final long maxWorkspaceFiles;
    private final long maxWorkspaceBytes;
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
                WorkerSandboxBackends.strongestAvailable(minosHome),
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
        if (maxWorkspaceFiles < 1 || maxWorkspaceBytes < 1) {
            throw new IllegalArgumentException("workspace limits must be positive");
        }
        this.maxWorkspaceFiles = maxWorkspaceFiles;
        this.maxWorkspaceBytes = maxWorkspaceBytes;
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
            deleteWorkerTree(workspace);
        }
        Files.createDirectory(workspace);

        Instant startedAt = clock.instant();
        try {
            copyWorkspace(request.execution().projectRoot(), workspace);
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
                    || !Files.isRegularFile(artifactPath, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(artifactPath) < 1L) {
                throw new IOException("worker sandbox did not produce a non-empty regular artifact");
            }
            Instant completedAt = clock.instant();
            DistributedArtifactManifest manifest = new DistributedArtifactManifest(
                    DistributedArtifactManifest.FORMAT_V1,
                    isolated.runId(),
                    isolated.projectId(),
                    request.sourceRepository(),
                    request.sourceCommit(),
                    artifact.language(),
                    artifact.indexerId(),
                    request.providerVersion(),
                    workerId,
                    isolation(),
                    request.networkPolicy(),
                    sandboxBackend.enforcesNetworkDeny(),
                    startedAt,
                    completedAt,
                    DistributedArtifactManifest.ARTIFACT_PATH,
                    Files.size(artifactPath),
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
                deleteWorkerTree(providerRoot);
            }
            try {
                Files.deleteIfExists(providerRoot.getParent());
            } catch (java.nio.file.DirectoryNotEmptyException ignored) {
                // Another provider from the same run still owns its isolated directory.
            }
        }
    }

    private void copyWorkspace(Path sourceRoot, Path targetRoot) throws IOException {
        Path source = Objects.requireNonNull(sourceRoot, "sourceRoot").toRealPath();
        if (!Files.isDirectory(source)) {
            throw new IOException("worker source workspace is not a directory");
        }
        long files = 0L;
        long bytes = 0L;
        try (var paths = Files.walk(source)) {
            for (Path current : paths.sorted().toList()) {
                Path relative = source.relativize(current);
                if (relative.getNameCount() > 0
                        && ".git".equals(relative.getName(0).toString())) {
                    continue;
                }
                if (Files.isSymbolicLink(current)) {
                    throw new IOException(
                            "remote worker rejects symbolic links: "
                                    + relative.toString().replace('\\', '/'));
                }
                Path target = targetRoot.resolve(relative).normalize();
                if (!target.startsWith(targetRoot)) {
                    throw new IOException("worker workspace path escapes its target root");
                }
                if (Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target);
                } else if (Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) {
                    files = Math.addExact(files, 1L);
                    bytes = Math.addExact(bytes, Files.size(current));
                    if (files > maxWorkspaceFiles || bytes > maxWorkspaceBytes) {
                        throw new IOException("remote worker workspace exceeds configured limits");
                    }
                    Files.createDirectories(target.getParent());
                    Files.copy(current, target, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
                } else {
                    throw new IOException("remote worker rejects non-regular workspace entries");
                }
            }
        }
    }

    private void deleteWorkerTree(Path target) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        if (normalized.equals(workersRoot) || !normalized.startsWith(workersRoot)) {
            throw new IOException("refusing to delete outside the distributed worker root");
        }
        try (var paths = Files.walk(normalized)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                clearReadOnly(path);
                Files.deleteIfExists(path);
            }
        }
    }

    private static void clearReadOnly(Path path) {
        try {
            DosFileAttributeView attributes = Files.getFileAttributeView(
                    path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes != null && attributes.readAttributes().isReadOnly()) {
                attributes.setReadOnly(false);
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // Non-DOS file systems do not need this Windows-specific cleanup.
        }
        path.toFile().setWritable(true);
    }
}
