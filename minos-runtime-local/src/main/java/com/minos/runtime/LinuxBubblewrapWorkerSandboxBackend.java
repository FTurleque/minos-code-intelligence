package com.minos.runtime;

import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.remote.DistributedIndexing.WorkerIsolation;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Linux worker sandbox backed by bubblewrap namespaces and util-linux primitives.
 *
 * <p>The host root is mounted read-only. Only the provider working directory and the generated
 * artifact directory are writable. Bubblewrap owns the user/mount/PID/IPC/UTS/cgroup boundaries.
 * For DENY, a nested util-linux {@code unshare --net} creates an empty network namespace without
 * asking bubblewrap to configure loopback; capabilities are then dropped with {@code setpriv}
 * before the provider starts. Resource limits are inherited from {@code prlimit}.</p>
 */
public final class LinuxBubblewrapWorkerSandboxBackend implements WorkerSandboxBackend {

    static final long MAX_ADDRESS_SPACE_BYTES = 8L * 1024L * 1024L * 1024L;
    static final int MAX_PROCESSES = 128;
    static final int MAX_OPEN_FILES = 2048;

    private final Path bubblewrap;
    private final Path prlimit;
    private final Path unshare;
    private final Path setpriv;

    public LinuxBubblewrapWorkerSandboxBackend(Path bubblewrap, Path prlimit, Path unshare, Path setpriv) {
        this.bubblewrap = regularExecutable(bubblewrap, "bubblewrap");
        this.prlimit = regularExecutable(prlimit, "prlimit");
        this.unshare = regularExecutable(unshare, "unshare");
        this.setpriv = regularExecutable(setpriv, "setpriv");
    }

    public static Optional<LinuxBubblewrapWorkerSandboxBackend> discover() {
        if (WorkerSandboxQualification.currentPlatform() != WorkerSandboxQualification.Platform.LINUX) {
            return Optional.empty();
        }
        Optional<Path> bwrap = CommandLocator.find("bwrap");
        Optional<Path> limits = CommandLocator.find("prlimit");
        Optional<Path> networkNamespace = CommandLocator.find("unshare");
        Optional<Path> privilegeDrop = CommandLocator.find("setpriv");
        if (bwrap.isEmpty() || limits.isEmpty() || networkNamespace.isEmpty() || privilegeDrop.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new LinuxBubblewrapWorkerSandboxBackend(
                bwrap.orElseThrow(),
                limits.orElseThrow(),
                networkNamespace.orElseThrow(),
                privilegeDrop.orElseThrow()));
    }

    @Override
    public String id() {
        return "linux-bubblewrap-unshare-prlimit-v2";
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
                        "LINUX_BUBBLEWRAP_USER_MOUNT_PID_IPC_UTS_CGROUP_NAMESPACES",
                        "LINUX_UTIL_LINUX_EMPTY_NETWORK_NAMESPACE_DENY",
                        "LINUX_HOST_ROOT_READ_ONLY",
                        "LINUX_WORKSPACE_AND_ARTIFACT_WRITE_ONLY",
                        "LINUX_PROVIDER_CAPABILITIES_DROPPED_AND_NO_NEW_PRIVS",
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
        // Keep host networking only during bubblewrap setup. DENY creates its own empty network
        // namespace below, avoiding bubblewrap's loopback configuration which is blocked on some
        // hardened hosted runners.
        sandbox.add("--share-net");
        if (networkPolicy == WorkerNetworkPolicy.ALLOW) {
            sandbox.add("--cap-drop");
            sandbox.add("ALL");
        }
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

        if (networkPolicy == WorkerNetworkPolicy.DENY) {
            sandbox.add(unshare.toString());
            sandbox.add("--net");
            sandbox.add("--");
            sandbox.add(setpriv.toString());
            sandbox.add("--no-new-privs");
            sandbox.add("--bounding-set=-all");
            sandbox.add("--inh-caps=-all");
            sandbox.add("--ambient-caps=-all");
            sandbox.add("--");
        }
        sandbox.addAll(plan.command());

        return new IndexerProcessPlan(
                List.copyOf(sandbox),
                plan.workingDirectory(),
                plan.environment(),
                plan.generatedArtifact(),
                plan.timeout());
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
