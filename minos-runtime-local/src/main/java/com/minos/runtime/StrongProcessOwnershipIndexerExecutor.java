package com.minos.runtime;

import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Fail-closed wrapper for provider executions that require kernel-backed descendant ownership.
 *
 * <p>{@link ProcessOwnershipTracker} remains active inside the delegate as defence in depth, but it
 * is deliberately not the ownership authority. Linux uses a cgroup v2 joined before {@code exec};
 * Windows uses a Job Object assigned while the provider process is still suspended. Unsupported
 * hosts never silently fall back to process-tree polling.</p>
 */
public final class StrongProcessOwnershipIndexerExecutor implements IndexerExecutor {

    private final ProcessIndexerExecutor delegate;
    private final Path minosHome;

    public StrongProcessOwnershipIndexerExecutor(ProcessIndexerExecutor delegate, Path minosHome) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.minosHome = Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize();
    }

    @Override
    public String indexerId() {
        return delegate.indexerId();
    }

    /** Returns the strong-ownership capability of the current host/configuration. */
    public Capability capability() {
        return switch (WorkerSandboxQualification.currentPlatform()) {
            case LINUX -> {
                Optional<Path> root = LinuxCgroupJob.delegatedRoot();
                Optional<Path> shell = CommandLocator.find("sh");
                if (root.isPresent() && shell.isPresent()) {
                    yield Capability.available("linux-cgroup-v2");
                }
                yield Capability.unavailable("linux-cgroup-v2",
                        root.isEmpty() ? "delegated cgroup v2 root is unavailable" : "POSIX sh is unavailable");
            }
            case WINDOWS -> WindowsJobObjectProcessOwnership.discover(minosHome).isPresent()
                    ? Capability.available("windows-job-object")
                    : Capability.unavailable("windows-job-object", "PowerShell/native Job Object launcher is unavailable");
            case OTHER -> Capability.unavailable("none", "strong process ownership is unsupported on this platform");
        };
    }

    @Override
    public IndexingArtifact execute(IndexingExecutionRequest request) throws Exception {
        Objects.requireNonNull(request, "request");
        ProcessIndexerExecutor.ProcessPlanTransformer transformer = switch (
                WorkerSandboxQualification.currentPlatform()) {
            case LINUX -> linuxTransformer(request);
            case WINDOWS -> WindowsJobObjectProcessOwnership.discover(minosHome)
                    .orElseThrow(() -> unavailable("Windows Job Object ownership is unavailable"))
                    .transformer();
            case OTHER -> throw unavailable("strong process ownership is unsupported on this platform");
        };
        return delegate.executeSandboxed(request, transformer);
    }

    private ProcessIndexerExecutor.ProcessPlanTransformer linuxTransformer(IndexingExecutionRequest request) {
        Path root = LinuxCgroupJob.delegatedRoot()
                .orElseThrow(() -> unavailable("delegated cgroup v2 ownership is unavailable"));
        Path shell = CommandLocator.find("sh")
                .orElseThrow(() -> unavailable("POSIX sh required by cgroup ownership launcher is unavailable"));
        String jobName = "minos-provider-" + request.runId();
        return new ProcessIndexerExecutor.ProcessPlanTransformer() {
            private LinuxCgroupJob job;

            @Override
            public IndexerProcessPlan transform(IndexerProcessPlan plan, Path runDirectory) throws Exception {
                if (job != null) throw new IllegalStateException("process ownership transformer is single-use");
                job = LinuxCgroupJob.create(root, jobName, LinuxCgroupJob.Limits.DEFAULT);
                return new IndexerProcessPlan(
                        job.enterThenExec(shell, plan.command()),
                        plan.workingDirectory(),
                        plan.environment(),
                        plan.generatedArtifact(),
                        plan.timeout());
            }

            @Override
            public void killContainedJob() {
                if (job != null) job.kill();
            }

            @Override
            public void releaseContainment() {
                if (job != null) {
                    job.close();
                    job = null;
                }
            }
        };
    }

    private static IllegalStateException unavailable(String detail) {
        return new IllegalStateException("strong provider process ownership is required but unavailable: " + detail);
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
            return new Capability(Status.UNAVAILABLE, mechanism, List.of(Objects.requireNonNull(diagnostic, "diagnostic")));
        }

        public boolean strong() {
            return status == Status.STRONG;
        }
    }
}
