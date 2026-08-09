package com.minos.runtime;

import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.remote.DistributedIndexing.WorkerIsolation;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Linux worker sandbox backed by bubblewrap namespaces and util-linux prlimit.
 *
 * <p>The host root is mounted read-only. Only the provider working directory, artifact directory
 * and MINOS run directory are writable. DENY keeps bubblewrap's isolated network namespace;
 * ALLOW explicitly shares the host network namespace. Provider capabilities are dropped in both
 * modes. Resource limits are inherited from {@code prlimit}.</p>
 *
 * <p>Discovery is capability-based rather than executable-presence-based: MINOS runs a bounded
 * probe of the same namespace and privilege chain before advertising {@code OS_ENFORCED}. On
 * kernels or LSM policies that reject unprivileged user namespaces, the backend is unavailable and
 * worker {@code DENY} remains fail-closed.</p>
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
        if (bwrap.isEmpty() || limits.isEmpty()) {
            return Optional.empty();
        }
        LinuxBubblewrapWorkerSandboxBackend candidate =
                new LinuxBubblewrapWorkerSandboxBackend(bwrap.orElseThrow(), limits.orElseThrow());
        return candidate.probeOsIsolation() ? Optional.of(candidate) : Optional.empty();
    }

    @Override
    public String id() {
        return "linux-bubblewrap-prlimit-v3";
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
                        "LINUX_BUBBLEWRAP_USER_MOUNT_PID_IPC_UTS_CGROUP_NETWORK_NAMESPACES",
                        "LINUX_HOST_ROOT_READ_ONLY",
                        "LINUX_WORKSPACE_ARTIFACT_RUN_WRITE_ONLY",
                        "LINUX_PROVIDER_CAPABILITIES_DROPPED",
                        "LINUX_PRLIMIT_ADDRESS_SPACE_PROCESS_COUNT_OPEN_FILES_CPU",
                        "LINUX_RUNTIME_CAPABILITY_PROBE_REQUIRED",
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
        Objects.requireNonNull(networkPolicy, "networkPolicy");
        Path working = plan.workingDirectory().toRealPath();
        Path artifact = plan.generatedArtifact().toAbsolutePath().normalize();
        Path artifactParent = Objects.requireNonNull(artifact.getParent(), "generated artifact parent");
        Files.createDirectories(artifactParent);
        artifactParent = artifactParent.toRealPath();
        Path run = runDirectory.toRealPath();

        List<String> sandbox = baseCommand(plan.timeout().plusSeconds(5).toSeconds(), networkPolicy);
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
                plan.environment(),
                plan.generatedArtifact(),
                plan.timeout());
    }

    private boolean probeOsIsolation() {
        List<String> command = baseCommand(5L, WorkerNetworkPolicy.DENY);
        command.add("--ro-bind");
        command.add("/");
        command.add("/");
        command.add("--dev");
        command.add("/dev");
        command.add("--proc");
        command.add("/proc");
        command.add("--");
        command.add("/bin/true");
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                return false;
            }
            process.getInputStream().readAllBytes();
            return process.exitValue() == 0;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private List<String> baseCommand(long cpuSeconds, WorkerNetworkPolicy networkPolicy) {
        List<String> sandbox = new ArrayList<>();
        sandbox.add(prlimit.toString());
        sandbox.add("--as=" + MAX_ADDRESS_SPACE_BYTES + ":" + MAX_ADDRESS_SPACE_BYTES);
        sandbox.add("--nproc=" + MAX_PROCESSES + ":" + MAX_PROCESSES);
        sandbox.add("--nofile=" + MAX_OPEN_FILES + ":" + MAX_OPEN_FILES);
        long boundedCpuSeconds = Math.max(1L, cpuSeconds);
        sandbox.add("--cpu=" + boundedCpuSeconds + ":" + boundedCpuSeconds);
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
        return sandbox;
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
