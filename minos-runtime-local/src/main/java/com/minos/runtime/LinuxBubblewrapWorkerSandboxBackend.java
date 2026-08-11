package com.minos.runtime;

import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.remote.DistributedIndexing.WorkerIsolation;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Linux worker sandbox backed by bubblewrap namespaces and util-linux prlimit.
 *
 * <p>The host root is never exposed wholesale. System runtime roots and the exact managed provider
 * runtime needed by the command are mounted read-only; arbitrary absolute files are mounted as
 * individual files instead of exposing their parent directories. The workspace, artifact and MINOS
 * run roots are writable. DENY keeps bubblewrap's isolated network namespace; ALLOW explicitly
 * shares the host network namespace.</p>
 */
public final class LinuxBubblewrapWorkerSandboxBackend implements WorkerSandboxBackend {

    static final long MAX_ADDRESS_SPACE_BYTES = 8L * 1024L * 1024L * 1024L;
    static final int MAX_PROCESSES = 128;
    static final int MAX_OPEN_FILES = 2048;

    private final Path minosHome;
    private final Path bubblewrap;
    private final Path prlimit;

    public LinuxBubblewrapWorkerSandboxBackend(Path bubblewrap, Path prlimit) {
        this(null, bubblewrap, prlimit);
    }

    LinuxBubblewrapWorkerSandboxBackend(Path minosHome, Path bubblewrap, Path prlimit) {
        this.minosHome = minosHome == null ? null : minosHome.toAbsolutePath().normalize();
        this.bubblewrap = regularExecutable(bubblewrap, "bubblewrap");
        this.prlimit = regularExecutable(prlimit, "prlimit");
    }

    public static Optional<LinuxBubblewrapWorkerSandboxBackend> discover() {
        return discover(null);
    }

    public static Optional<LinuxBubblewrapWorkerSandboxBackend> discover(Path minosHome) {
        if (WorkerSandboxQualification.currentPlatform() != WorkerSandboxQualification.Platform.LINUX) {
            return Optional.empty();
        }
        Optional<Path> bwrap = CommandLocator.find("bwrap");
        Optional<Path> limits = CommandLocator.find("prlimit");
        if (bwrap.isEmpty() || limits.isEmpty()) {
            return Optional.empty();
        }
        LinuxBubblewrapWorkerSandboxBackend candidate =
                new LinuxBubblewrapWorkerSandboxBackend(minosHome, bwrap.orElseThrow(), limits.orElseThrow());
        return candidate.probeOsIsolation() ? Optional.of(candidate) : Optional.empty();
    }

    @Override
    public String id() {
        return "linux-bubblewrap-prlimit-v4";
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
                        "LINUX_MINIMAL_RUNTIME_READ_ONLY_ALLOWLIST",
                        "LINUX_EXACT_FILE_BIND_FOR_EXTERNAL_PROVIDER_ARGUMENTS",
                        "LINUX_MANAGED_PROVIDER_RUNTIME_ROOT_ONLY",
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
        addRuntimeReadOnlyBinds(sandbox, plan.command(), networkPolicy, minosHome);
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
        try {
            addRuntimeReadOnlyBinds(command, List.of("/bin/true"), WorkerNetworkPolicy.DENY, minosHome);
        } catch (IOException exception) {
            return false;
        }
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
                System.err.println("MINOS Linux sandbox capability probe timed out");
                return false;
            }
            String output = new String(process.getInputStream().readNBytes(8192), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                System.err.println("MINOS Linux sandbox capability probe failed (exit="
                        + process.exitValue() + "): " + output);
                return false;
            }
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException exception) {
            System.err.println("MINOS Linux sandbox capability probe could not start: "
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage());
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

    private static void addRuntimeReadOnlyBinds(
            List<String> command,
            List<String> providerCommand,
            WorkerNetworkPolicy networkPolicy,
            Path minosHome
    ) throws IOException {
        Set<Path> mounted = new LinkedHashSet<>();
        for (String root : List.of("/usr", "/bin", "/lib", "/lib64", "/sbin")) {
            addReadOnlyIfPresent(command, mounted, Path.of(root));
        }
        if (networkPolicy == WorkerNetworkPolicy.ALLOW) {
            for (String value : List.of(
                    "/etc/ssl", "/etc/ca-certificates", "/etc/resolv.conf", "/etc/hosts",
                    "/etc/nsswitch.conf", "/etc/passwd", "/etc/group", "/etc/ld.so.cache")) {
                addReadOnlyIfPresent(command, mounted, Path.of(value));
            }
        }

        Path tools = minosHome == null ? null : minosHome.resolve("tools").toAbsolutePath().normalize();
        for (String argument : providerCommand) {
            try {
                Path candidate = Path.of(argument);
                if (!candidate.isAbsolute() || !Files.exists(candidate)) continue;
                Path normalized = candidate.toAbsolutePath().normalize();
                Path real = candidate.toRealPath();
                if (mounted.stream().anyMatch(real::startsWith)) continue;

                Path managedRoot = managedRuntimeRoot(normalized, tools);
                if (managedRoot != null) {
                    addReadOnlyIfPresent(command, mounted, managedRoot);
                    continue;
                }
                if (Files.isRegularFile(real)) {
                    addReadOnlyFile(command, mounted, real, normalized);
                }
                // Arbitrary directories are deliberately not promoted to read roots. Project,
                // artifact and run directories are mounted explicitly by sandboxPlan.
            } catch (RuntimeException ignored) {
                // Non-path provider arguments stay opaque.
            }
        }
    }

    static Path managedRuntimeRoot(Path candidate, Path tools) {
        if (tools == null || !candidate.startsWith(tools)) return null;
        Path relative = tools.relativize(candidate);
        if (relative.getNameCount() < 2) return null;
        return tools.resolve(relative.subpath(0, 2));
    }

    private static void addReadOnlyIfPresent(List<String> command, Set<Path> mounted, Path candidate)
            throws IOException {
        if (candidate == null || !Files.exists(candidate)) return;
        Path destination = candidate.toAbsolutePath().normalize();
        Path real = candidate.toRealPath();
        if (mounted.stream().anyMatch(real::startsWith)) {
            if (Files.isSymbolicLink(destination)) {
                addDestinationParents(command, destination);
                command.add("--symlink");
                command.add(Files.readSymbolicLink(destination).toString());
                command.add(destination.toString());
            }
            return;
        }
        if (!mounted.add(real)) return;
        addDestinationParents(command, destination);
        command.add("--ro-bind");
        command.add(real.toString());
        command.add(destination.toString());
    }

    private static void addReadOnlyFile(
            List<String> command,
            Set<Path> mounted,
            Path realSource,
            Path destination
    ) {
        Path marker = destination.toAbsolutePath().normalize();
        if (!mounted.add(marker)) return;
        addDestinationParents(command, marker);
        command.add("--ro-bind");
        command.add(realSource.toString());
        command.add(marker.toString());
    }

    private static void addWritableBind(List<String> command, Path directory) {
        addDestinationParents(command, directory);
        command.add("--bind");
        command.add(directory.toString());
        command.add(directory.toString());
    }

    private static void addDestinationParents(List<String> command, Path destination) {
        Path absolute = destination.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) return;
        List<Path> parents = new ArrayList<>();
        while (parent != null && parent.getParent() != null) {
            parents.add(parent);
            parent = parent.getParent();
        }
        for (int index = parents.size() - 1; index >= 0; index--) {
            command.add("--dir");
            command.add(parents.get(index).toString());
        }
    }

    private static Path regularExecutable(Path value, String label) {
        Path path = Objects.requireNonNull(value, label).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path) || !Files.isExecutable(path)) {
            throw new IllegalArgumentException(label + " must be an executable regular file: " + path);
        }
        return path;
    }
}
