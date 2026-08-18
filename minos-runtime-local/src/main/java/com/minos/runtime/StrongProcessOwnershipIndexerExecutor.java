package com.minos.runtime;

import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Fail-closed wrapper for provider executions that require kernel-backed descendant ownership.
 *
 * <p>The historical two-argument constructor retains its ownership-only contract for callers that
 * explicitly qualify that kernel primitive. Managed provider production wiring uses the explicit
 * network-policy constructor, which additionally executes from a bounded ephemeral project copy
 * inside the strongest qualified OS sandbox.</p>
 */
public final class StrongProcessOwnershipIndexerExecutor implements ProcessSandboxCapableIndexerExecutor {

    private final ProcessIndexerExecutor delegate;
    private final BoundaryProvider boundaryProvider;
    private final LocalIsolation localIsolation;

    /** Strong descendant ownership only; managed providers must use the explicit network-policy constructor. */
    public StrongProcessOwnershipIndexerExecutor(ProcessIndexerExecutor delegate, Path minosHome) {
        Path home = normalizedHome(minosHome);
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.boundaryProvider = platformBoundary(home);
        this.localIsolation = null;
    }

    /**
     * Production managed-provider boundary: copied workspace plus qualified OS sandbox and an
     * explicit provider network policy.
     */
    public StrongProcessOwnershipIndexerExecutor(
            ProcessIndexerExecutor delegate,
            Path minosHome,
            WorkerNetworkPolicy networkPolicy
    ) {
        Path home = normalizedHome(minosHome);
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.boundaryProvider = platformBoundary(home);
        this.localIsolation = new LocalIsolation(
                home,
                Objects.requireNonNull(networkPolicy, "networkPolicy"));
    }

    StrongProcessOwnershipIndexerExecutor(ProcessIndexerExecutor delegate, BoundaryProvider boundaryProvider) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.boundaryProvider = Objects.requireNonNull(boundaryProvider, "boundaryProvider");
        this.localIsolation = null;
    }

    @Override
    public String indexerId() {
        return delegate.indexerId();
    }

    /** Probes the same strong-ownership capability used by the ownership-only boundary. */
    public static Capability detectCapability(Path minosHome) {
        return Objects.requireNonNull(
                platformBoundary(normalizedHome(minosHome)).capability(),
                "ownership capability");
    }

    /** Returns the strong-ownership capability of the current host/configuration. */
    public Capability capability() {
        return Objects.requireNonNull(boundaryProvider.capability(), "ownership capability");
    }

    @Override
    public IndexingArtifact execute(IndexingExecutionRequest request) throws Exception {
        Objects.requireNonNull(request, "request");
        if (localIsolation != null) {
            return executeLocallyIsolated(request, localIsolation);
        }
        return executeOwnershipOnly(request);
    }

    private IndexingArtifact executeLocallyIsolated(
            IndexingExecutionRequest request,
            LocalIsolation isolation
    ) throws Exception {
        WorkerSandboxBackend backend = WorkerSandboxBackends.strongestAvailable(isolation.minosHome());
        if (!backend.supportsUntrustedCode()) {
            throw unavailable("qualified local provider sandbox is unavailable: " + backend.id());
        }
        if (isolation.networkPolicy() == WorkerNetworkPolicy.DENY && !backend.enforcesNetworkDeny()) {
            throw unavailable("qualified local provider sandbox cannot prove OS-level network denial: " + backend.id());
        }

        try (LocalProviderWorkspace workspace = LocalProviderWorkspace.create(isolation.minosHome(), request)) {
            IndexingExecutionRequest isolatedRequest = workspace.request();
            IndexingArtifact artifact = Objects.requireNonNull(
                    backend.execute(delegate, isolatedRequest, isolation.networkPolicy()),
                    "local provider sandbox artifact");
            if (artifact.language() != request.selection().language()
                    || !artifact.indexerId().equals(delegate.indexerId())
                    || !artifact.projectRelativeRoot().equals(request.projectRelativeRoot())) {
                throw new IllegalStateException("local provider sandbox returned inconsistent artifact provenance");
            }
            return artifact;
        }
    }

    private IndexingArtifact executeOwnershipOnly(IndexingExecutionRequest request) throws Exception {
        Capability capability = capability();
        if (!capability.strong()) {
            throw unavailable(String.join("; ", capability.diagnostics()));
        }
        ProcessIndexerExecutor.ProcessPlanTransformer transformer = Objects.requireNonNull(
                boundaryProvider.transformer(request), "strong ownership transformer");
        return delegate.executeSandboxed(request, transformer);
    }

    /**
     * Executes through an independently qualified sandbox boundary supplied by the remote worker.
     * The sandbox becomes the ownership authority for this execution, so the standalone ownership
     * boundary is deliberately not nested around it. ProcessOwnershipTracker remains active inside
     * the delegate as defence in depth.
     */
    @Override
    public IndexingArtifact executeSandboxed(
            IndexingExecutionRequest request,
            ProcessIndexerExecutor.ProcessPlanTransformer transformer
    ) throws Exception {
        return delegate.executeSandboxed(
                Objects.requireNonNull(request, "request"),
                Objects.requireNonNull(transformer, "transformer"));
    }

    private static Path normalizedHome(Path minosHome) {
        return Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize();
    }

    private static BoundaryProvider platformBoundary(Path minosHome) {
        return new BoundaryProvider() {
            @Override
            public Capability capability() {
                return switch (WorkerSandboxQualification.currentPlatform()) {
                    case LINUX -> {
                        Optional<Path> root = LinuxCgroupJob.delegatedRoot();
                        Optional<Path> shell = CommandLocator.find("sh");
                        if (root.isPresent() && shell.isPresent()) {
                            yield Capability.available("linux-cgroup-v2");
                        }
                        yield Capability.unavailable("linux-cgroup-v2",
                                root.isEmpty()
                                        ? "delegated cgroup v2 root with cgroup.kill is unavailable"
                                        : "POSIX sh is unavailable");
                    }
                    case WINDOWS -> WindowsJobObjectProcessOwnership.discover(minosHome).isPresent()
                            ? Capability.available("windows-job-object")
                            : Capability.unavailable(
                                    "windows-job-object",
                                    "PowerShell/native Job Object launcher is unavailable");
                    case OTHER -> Capability.unavailable(
                            "none", "strong process ownership is unsupported on this platform");
                };
            }

            @Override
            public ProcessIndexerExecutor.ProcessPlanTransformer transformer(IndexingExecutionRequest request) {
                return switch (WorkerSandboxQualification.currentPlatform()) {
                    case LINUX -> linuxTransformer(request);
                    case WINDOWS -> WindowsJobObjectProcessOwnership.discover(minosHome)
                            .orElseThrow(() -> unavailable("Windows Job Object ownership is unavailable"))
                            .transformer();
                    case OTHER -> throw unavailable("strong process ownership is unsupported on this platform");
                };
            }
        };
    }

    private static ProcessIndexerExecutor.ProcessPlanTransformer linuxTransformer(IndexingExecutionRequest request) {
        Path root = LinuxCgroupJob.delegatedRoot()
                .orElseThrow(() -> unavailable("delegated cgroup v2 ownership is unavailable"));
        Path shell = CommandLocator.find("sh")
                .orElseThrow(() -> unavailable("POSIX sh required by cgroup ownership launcher is unavailable"));
        String jobName = "minos-provider-" + request.runId();
        return new ProcessIndexerExecutor.ProcessPlanTransformer() {
            private LinuxCgroupJob job;
            private RuntimeException deferredContainmentFailure;

            @Override
            public IndexerProcessPlan transform(IndexerProcessPlan plan, Path runDirectory) throws Exception {
                if (job != null) throw new IllegalStateException("process ownership transformer is single-use");
                job = LinuxCgroupJob.createOwnershipOnly(root, jobName);
                return new IndexerProcessPlan(
                        job.enterThenExec(shell, plan.command()),
                        plan.workingDirectory(),
                        plan.environment(),
                        plan.generatedArtifact(),
                        plan.timeout());
            }

            @Override
            public synchronized void killContainedJob() {
                if (job == null) return;
                try {
                    job.kill();
                } catch (RuntimeException failure) {
                    rememberContainmentFailure(failure);
                }
            }

            @Override
            public synchronized void releaseContainment() {
                if (job == null) return;
                RuntimeException failure = deferredContainmentFailure;
                try {
                    job.close();
                } catch (RuntimeException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else if (failure != closeFailure) failure.addSuppressed(closeFailure);
                } finally {
                    job = null;
                    deferredContainmentFailure = null;
                }
                if (failure != null) throw failure;
            }

            private void rememberContainmentFailure(RuntimeException failure) {
                if (deferredContainmentFailure == null) {
                    deferredContainmentFailure = failure;
                } else if (deferredContainmentFailure != failure) {
                    deferredContainmentFailure.addSuppressed(failure);
                }
            }
        };
    }

    private static IllegalStateException unavailable(String detail) {
        return new IllegalStateException("strong provider process ownership is required but unavailable: " + detail);
    }

    interface BoundaryProvider {
        Capability capability();

        ProcessIndexerExecutor.ProcessPlanTransformer transformer(IndexingExecutionRequest request);
    }

    private record LocalIsolation(Path minosHome, WorkerNetworkPolicy networkPolicy) {
        private LocalIsolation {
            minosHome = normalizedHome(minosHome);
            Objects.requireNonNull(networkPolicy, "networkPolicy");
        }
    }

    public record Capability(Status status, String mechanism, List<String> diagnostics) {
        public enum Status { STRONG, UNAVAILABLE }

        public Capability {
            status = Objects.requireNonNull(status, "status");
            if (mechanism == null || mechanism.isBlank()) {
                throw new IllegalArgumentException("mechanism must not be blank");
            }
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        }

        public static Capability available(String mechanism) {
            return new Capability(Status.STRONG, mechanism, List.of());
        }

        public static Capability unavailable(String mechanism, String diagnostic) {
            return new Capability(
                    Status.UNAVAILABLE,
                    mechanism,
                    List.of(Objects.requireNonNull(diagnostic, "diagnostic")));
        }

        public boolean strong() {
            return status == Status.STRONG;
        }
    }
}
