package com.minos.runtime;

import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.remote.DistributedIndexing.WorkerIsolation;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Linux worker sandbox backed by bubblewrap namespaces and util-linux prlimit.
 *
 * <p>The host root is mounted read-only. Only the provider working directory and the generated
 * artifact directory are writable. DENY gets a fresh network namespace. The provider is also
 * bounded by address-space, process-count, descriptor and CPU rlimits in addition to the existing
 * MINOS wall-clock timeout and workspace byte/file quotas.</p>
 */
public final class LinuxBubblewrapWorkerSandboxBackend implements WorkerSandboxBackend {

    static final long MAX_ADDRESS_SPACE_BYTES = 8L * 1024L * 1024L * 1024L;
    static final int MAX_PROCESSES = 128;
    static final int MAX_OPEN_FILES = 2048;

    private final Path bubblewrap;
    private final Path prlimit;

    public LinuxBubblewrapWorkerSandboxBackend(Path bubblewrap, Path prlimit) {
        this.bubblewrap = regularExecutable(bubblewrap, "bubblewrap");
        this.prlimit = regularExecutable(prlimit, "prlimit");
    }

    public static Optional<LinuxBubblewrapWorkerSandboxBackend> discover() {
        if (WorkerSandboxQualification.currentPlatform() != WorkerSandboxQualification.Platform.LINUX) {
            return Optional.empty();
        }
        Optional<Path> bwrap = CommandLocator.find("bwrap");
        Optional<Path> limits = CommandLocator.find("prlimit");
        if (bwrap.isEmpty() || limits.isEmpty()) return Optional.empty();
        return Optional.of(new LinuxBubblewrapWorkerSandboxBackend(bwrap.orElseThrow(), limits.orElseThrow()));
    }

    @Override
    public String id() {
        return "linux-bubblewrap-prlimit-v1";
    }

    @Override
    public WorkerIsolation isolation() {
        return WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE;
    }

    @Override
    public NetworkGuarantee networkGuarantee() {
        return NetworkGuarantee.OS_ENFORCED;
    }

    @Override
    public WorkerSandboxQualification qualification() {
        return new WorkerSandboxQualification(
                id(),
                isolation(),
                networkGuarantee(),
                WorkerSandboxQualification.NetworkDenyDisposition.QUALIFIED,
                WorkerSandboxQualification.TrustDisposition.UNTRUSTED_CODE_SUPPORTED,
                Map.of(
                        WorkerSandboxQualification.Platform.LINUX,
                        WorkerSandboxQualification.PlatformDisposition.QUALIFIED,
                        WorkerSandboxQualification.Platform.WINDOWS,
                        WorkerSandboxQualification.PlatformDisposition.NOT_APPLICABLE,
                        WorkerSandboxQualification.Platform.OTHER,
                        WorkerSandboxQualification.PlatformDisposition.NOT_APPLICABLE),
                List.of(
                        "LINUX_BUBBLEWRAP_UNSHARE_ALL",
                        "LINUX_NETWORK_NAMESPACE_DENY",
                        "LINUX_HOST_ROOT_READ_ONLY",
                        "LINUX_WORKSPACE_AND_ARTIFACT_WRITE_ONLY",
                        "LINUX_CAPABILITIES_DROPPED",
                        "LINUX_PRLIMIT_ADDRESS_SPACE_PROCESS_COUNT_OPEN_FILES_CPU",
                        "MINOS_WALL_CLOCK_TIMEOUT_AND_WORKSPACE_QUOTAS_RETAINED"));
    }

    @Override
    public IndexingArtifact execute(
            IndexerExecutor delegate,
            IndexingExecutionRequest request,
            WorkerNetworkPolicy networkPolicy
    ) throws Exception {
        Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(networkPolicy, "networkPolicy");
        if (!qualification().qualifiedForCurrentPlatform()) {
            throw new IllegalStateException("Linux bubblewrap backend is not qualified on this platform");
        }
        if (!(delegate instanceof ProcessIndexerExecutor processExecutor)) {
            throw new IllegalArgumentException(
                    "qualified OS sandbox requires ProcessIndexerExecutor so MINOS controls the actual provider process");
        }
        return processExecutor.executeSandboxed(
                request,
                (plan, runDirectory) -> sandboxPlan(plan, runDirectory, networkPolicy));
    }

    IndexerProcessPlan sandboxPlan(
            IndexerProcessPlan plan,
            Path runDirectory,
            WorkerNetworkPolicy networkPolicy
    ) throws Exception {
        Objects.requireNonNull(plan, "plan");
        Path working = plan.workingDirectory().toRealPath();
        Path artifact = plan.generatedArtifact().toAbsolutePath().normalize();
        Path artifactParent = Objects.requireNonNull(artifact.getParent(), "generated artifact parent");
        Files.createDirectories(artifactParent);
        artifactParent = artifactParent.toRealPath();
        Path run = runDirectory.toRealPath();

        List<String> sandbox = new ArrayList<>();
        sandbox.add(prlimit.toString());
        sandbox.add("--as=" + MAX_ADDRESS_SPACE_BYTES + ":" + MAX_ADDRESS_SPACE_BYTES);
        sandbox.add("--nproc=" + MAX_PROCESSES + ":" + MAX_PROCESSES);
        sandbox.add("--nofile=" + MAX_OPEN_FILES + ":" + MAX_OPEN_FILES);
        long cpuSeconds = Math.max(1L, plan.timeout().plusSeconds(5).toSeconds());
        sandbox.add("--cpu=" + cpuSeconds + ":" + cpuSeconds);
        sandbox.add("--");

        sandbox.add(bubblewrap.toString());
        sandbox.add("--die-with-parent");
        sandbox.add("--new-session");
        sandbox.add("--unshare-all");
        if (networkPolicy == WorkerNetworkPolicy.ALLOW) {
            sandbox.add("--share-net");
        }
        sandbox.add("--cap-drop");
        sandbox.add("ALL");
        sandbox.add("--ro-bind");
        sandbox.add("/");
        sandbox.add("/");
        sandbox.add("--dev");
        sandbox.add("/dev");
        sandbox.add("--proc");
        sandbox.add("/proc");
        sandbox.add("--tmpfs");
        sandbox.add("/tmp");
        addWritableBind(sandbox, working);
        if (!artifactParent.startsWith(working)) addWritableBind(sandbox, artifactParent);
        if (!run.startsWith(working) && !run.startsWith(artifactParent)) addWritableBind(sandbox, run);
        sandbox.add("--chdir");
        sandbox.add(working.toString());
        sandbox.add("--");
        sandbox.addAll(plan.command());

        return new IndexerProcessPlan(
                List.copyOf(sandbox),
                plan.workingDirectory(),
                plan.generatedArtifact(),
                plan.timeout(),
                plan.environment());
    }

    private static void addWritableBind(List<String> command, Path directory) {
        command.add("--bind");
        command.add(directory.toString());
        command.add(directory.toString());
    }

    private static Path regularExecutable(Path value, String label) {
        Path path = Objects.requireNonNull(value, label).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path) || !Files.isExecutable(path)) {
            throw new IllegalArgumentException(label + " must be an executable regular file: " + path);
        }
        return path;
    }
}
