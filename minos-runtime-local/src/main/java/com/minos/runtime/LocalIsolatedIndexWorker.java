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
import com.minos.source.SourceBudgetPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;

/**
 * Provider worker with copied ephemeral workspace and explicit sandbox backend.
 *
 * <p>The public constructor selects the strongest qualified OS sandbox only when MINOS owns the
 * actual provider process through {@link ProcessIndexerExecutor}. Other executor implementations
 * fall back to the process-only backend: they cannot be truthfully wrapped by an OS process sandbox,
 * and network {@code DENY} therefore remains fail-closed.</p>
 */
public final class LocalIsolatedIndexWorker implements Worker {

    private static final long DEFAULT_MAX_WORKSPACE_FILES = SourceBudgetPolicy.DEFAULT_MAX_FILES;
    private static final long DEFAULT_MAX_WORKSPACE_BYTES = SourceBudgetPolicy.DEFAULT_MAX_BYTES;

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

    private static WorkerSandboxBackend defaultSandboxBackend(Path minosHome, IndexerExecutor delegate) {
        Objects.requireNonNull(minosHome, "minosHome");
        Objects.requireNonNull(delegate, "delegate");
        return delegate instanceof ProcessIndexerExecutor
                ? WorkerSandboxBackends.strongestAvailable(minosHome)
                : WorkerSandboxBackend.nativeEphemeralWorkspace();
    }

    private void copyWorkspace(Path sourceRoot, Path targetRoot) throws IOException {
        Path source = Objects.requireNonNull(sourceRoot, "sourceRoot").toRealPath();
        if (!Files.isDirectory(source)) {
            throw new IOException("worker source workspace is not a directory");
        }
        SourceBudgetPolicy.Tracker budget = sourceBudgetPolicy.tracker("remote worker workspace");
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                budget.accountTraversalEntry();
                Path relative = source.relativize(directory);
                if (isGitPath(relative)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (Files.isSymbolicLink(directory)) {
                    throw new IOException("remote worker rejects symbolic links: " + portable(relative));
                }
                Path target = checkedTarget(targetRoot, relative);
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                budget.accountTraversalEntry();
                Path relative = source.relativize(file);
                if (isGitPath(relative)) {
                    return FileVisitResult.CONTINUE;
                }
                if (Files.isSymbolicLink(file)) {
                    throw new IOException("remote worker rejects symbolic links: " + portable(relative));
                }
                if (!attributes.isRegularFile()) {
                    throw new IOException("remote worker rejects non-regular workspace entries");
                }
                budget.accountFile();
                Path target = checkedTarget(targetRoot, relative);
                Files.createDirectories(target.getParent());
                copyBounded(file, target, budget);
                Files.setLastModifiedTime(target, attributes.lastModifiedTime());
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void copyBounded(Path source, Path target, SourceBudgetPolicy.Tracker budget) throws IOException {
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
             OutputStream output = Files.newOutputStream(
                     target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                budget.accountBytes(read);
                output.write(buffer, 0, read);
            }
        } catch (Exception exception) {
            Files.deleteIfExists(target);
            throw exception;
        }
    }

    private static boolean isGitPath(Path relative) {
        return relative.getNameCount() > 0 && ".git".equals(relative.getName(0).toString());
    }

    private static Path checkedTarget(Path targetRoot, Path relative) throws IOException {
        Path target = targetRoot.resolve(relative).normalize();
        if (!target.startsWith(targetRoot)) {
            throw new IOException("worker workspace path escapes its target root");
        }
        return target;
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
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
    }
}
