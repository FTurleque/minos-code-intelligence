package com.minos.runtime.local.wsl;

import com.minos.diagnostics.PublicErrorMessages;
import com.minos.io.BoundedProperties;
import com.minos.io.FileTreeOperations;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.remote.DistributedArtifactManifest;
import com.minos.remote.DistributedIndexing.WorkerIsolation;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeStatus;
import com.minos.runtime.local.distributed.DistributedArtifactBundleStore;
import com.minos.runtime.local.sandbox.WorkerSandboxBackend;
import com.minos.runtime.local.sandbox.WorkerSandboxBackends;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Linux side of the WSL2 route: the internal worker command of the jar, run in the distribution.
 *
 * <p>It executes one provider and nothing else: no orchestration, no promotion, no retention. Before the provider
 * starts, it requires the backend chosen by {@link WorkerSandboxBackends#strongestAvailableForManagedLocalProvider}
 * to be qualified and to enforce {@code DENY} (which needs the delegated cgroup of the systemd scope), and the
 * provider runtime of the distribution to be ready; otherwise it refuses without a fallback. The artifact is
 * written as a {@code minos-distributed-artifact-v3} bundle next to the request, and the run directory of the
 * distribution is deleted.</p>
 *
 * <p>The outcome is one status line on standard output, prefixed by {@link WslIndexWorker#STATUS_PREFIX}; its
 * detail names no absolute path.</p>
 */
public final class WslLinuxWorker {

    /** The only provider the route runs: the one that is blocked under the Windows sandbox. */
    public static final String ACCEPTED_PROVIDER = "scip-java";

    public static final int SUCCESS = 0;
    public static final int FAILED = 1;
    public static final int INVALID_REQUEST = 2;

    private static final long MAX_REQUEST_BYTES = 16L * 1024L;

    private final Path minosHome;
    private final Function<Path, WorkerSandboxBackend> sandboxSelector;

    /**
     * @param minosHome       MINOS_HOME of the distribution, which holds the provider runtimes and the run directory
     * @param sandboxSelector selection of the managed local provider sandbox
     */
    public WslLinuxWorker(Path minosHome, Function<Path, WorkerSandboxBackend> sandboxSelector) {
        this.minosHome = Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize();
        this.sandboxSelector = Objects.requireNonNull(sandboxSelector, "sandboxSelector");
    }

    /** The worker of this host: the real managed local provider sandbox selection. */
    public static WslLinuxWorker production(Path minosHome) {
        return new WslLinuxWorker(minosHome, WorkerSandboxBackends::strongestAvailableForManagedLocalProvider);
    }

    /**
     * Runs the request file and writes the status line on {@code output}.
     *
     * @param runtimes    provider runtimes of the distribution
     * @param descriptors qualified indexer descriptors of this jar
     * @return {@link #SUCCESS}, {@link #FAILED} or {@link #INVALID_REQUEST}
     */
    public int run(Path requestFile, ProviderRuntimeManager runtimes, List<IndexerDescriptor> descriptors,
                   Appendable output) throws IOException {
        Objects.requireNonNull(requestFile, "requestFile");
        Objects.requireNonNull(runtimes, "runtimes");
        Objects.requireNonNull(descriptors, "descriptors");
        Objects.requireNonNull(output, "output");
        WslWorkerRequest request;
        try {
            request = WslWorkerRequest.decode(
                    BoundedProperties.readUtf8(requestFile, MAX_REQUEST_BYTES, "WSL worker request")
                            .getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException invalid) {
            return status(output, INVALID_REQUEST, "REFUSED invalid worker request: "
                    + publicMessage(invalid));
        }
        Path runDirectory = minosHome.resolve("runs").resolve(request.runId().toString());
        try {
            Optional<String> refusal = refusal(request, runtimes);
            if (refusal.isPresent()) {
                return status(output, FAILED, "REFUSED " + refusal.orElseThrow());
            }
            IndexerDescriptor descriptor = descriptors.stream()
                    .filter(candidate -> candidate.id().equals(request.providerId()))
                    .findFirst()
                    .orElse(null);
            if (descriptor == null || !descriptor.version().equals(request.providerVersion())) {
                return status(output, FAILED, "REFUSED " + request.providerId() + " " + request.providerVersion()
                        + " is not the provider version of this jar");
            }
            Path projectRoot = Path.of(request.projectRoot());
            if (!Files.isDirectory(projectRoot, LinkOption.NOFOLLOW_LINKS)) {
                return status(output, FAILED, "REFUSED the project is not reachable from the distribution");
            }
            produceBundle(request, descriptor, projectRoot, runtimes,
                    requestFile.toAbsolutePath().normalize().resolveSibling(WslWorkerRequest.BUNDLE_NAME));
            return status(output, SUCCESS, "OK");
        } catch (Exception failure) {
            return status(output, FAILED, "FAILED " + request.providerId() + ": " + publicMessage(failure));
        } finally {
            if (Files.exists(runDirectory, LinkOption.NOFOLLOW_LINKS)) {
                FileTreeOperations.deleteRecursively(runDirectory);
            }
        }
    }

    /** Why the provider must not start: provider, sandbox, then runtime, in that order. */
    private Optional<String> refusal(WslWorkerRequest request, ProviderRuntimeManager runtimes) {
        if (!ACCEPTED_PROVIDER.equals(request.providerId())) {
            return Optional.of("the WSL route runs " + ACCEPTED_PROVIDER + " only");
        }
        WorkerSandboxBackend backend = Objects.requireNonNull(sandboxSelector.apply(minosHome), "sandbox backend");
        if (!backend.supportsManagedLocalProvider() || !backend.enforcesNetworkDeny()) {
            return Optional.of(WslCheck.CGROUP_DELEGATION + ": no qualified Linux sandbox with a delegated cgroup v2 "
                    + "and network denial in the distribution (" + backend.id() + "); the worker must run in a "
                    + "systemd user scope with Delegate=yes and bubblewrap installed");
        }
        ProviderRuntimeStatus status = runtimes.inspect(request.providerId());
        if (!status.ready()) {
            String diagnostics = status.diagnostics().stream()
                    .map(diagnostic -> PublicErrorMessages.looksSensitive(diagnostic)
                            ? "a prerequisite that names a local path (withheld)"
                            : diagnostic)
                    .collect(Collectors.joining("; "));
            String action = status.state() == ProviderRuntimeStatus.State.NOT_INSTALLED
                    ? "; install it in the distribution with the tools install command of the MINOS jar"
                    : "";
            return Optional.of(request.providerId() + " is not ready in the distribution (" + status.state() + ")"
                    + (diagnostics.isEmpty() ? "" : ": " + diagnostics) + action);
        }
        return Optional.empty();
    }

    private void produceBundle(WslWorkerRequest request, IndexerDescriptor descriptor, Path projectRoot,
                               ProviderRuntimeManager runtimes, Path bundle) throws Exception {
        Path scope = request.projectRelativeRoot().isEmpty() ? Path.of("") : Path.of(request.projectRelativeRoot());
        IndexingExecutionRequest execution = new IndexingExecutionRequest(
                request.runId(), request.projectId(), projectRoot, projectRoot.resolve(scope).normalize(), scope,
                new IndexerSelection(request.language(), descriptor), IndexingMode.FULL, List.of(), Optional.empty());
        Instant startedAt = Instant.now();
        IndexingArtifact artifact = runtimes.executor(request.providerId()).execute(execution);
        Instant completedAt = Instant.now();
        if (artifact.language() != request.language() || !artifact.indexerId().equals(request.providerId())
                || !WslArtifactProvenance.portableScope(artifact.projectRelativeRoot())
                        .equals(request.projectRelativeRoot())) {
            throw new IllegalStateException("the provider returned an artifact of another provenance");
        }
        Path file = artifact.finalArtifact();
        long size = Files.size(file);
        DistributedArtifactManifest manifest = DistributedArtifactManifest.localWsl(
                request.runId(), request.projectId(), request.projectRelativeRoot(), request.projectFingerprint(),
                request.language(), request.providerId(), request.providerVersion(), request.workerId(),
                WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE, startedAt, completedAt, size,
                DistributedArtifactBundleStore.sha256(file));
        new DistributedArtifactBundleStore(minosHome).createBundle(bundle, manifest, file);
    }

    private static int status(Appendable output, int code, String line) throws IOException {
        output.append(WslIndexWorker.STATUS_PREFIX).append(line).append('\n');
        return code;
    }

    private static String publicMessage(Exception failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank() || PublicErrorMessages.looksSensitive(message)) {
            return failure.getClass().getSimpleName();
        }
        return message.replace('\n', ' ');
    }
}
