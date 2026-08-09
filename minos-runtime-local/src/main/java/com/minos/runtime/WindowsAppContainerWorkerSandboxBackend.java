package com.minos.runtime;

import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.remote.DistributedIndexing.WorkerIsolation;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Windows worker sandbox backed by an AppContainer token without capabilities and a Job Object.
 *
 * <p>The AppContainer capability set is deliberately empty: the child receives no Internet/client
 * or server network capability. The launcher verifies {@code TokenIsAppContainer} before resuming
 * the provider. A Job Object adds kill-on-close, active-process, memory and CPU limits. ACL grants
 * are scoped to the provider runtime/read roots plus the ephemeral workspace/artifact/run roots and
 * are removed when the child terminates.</p>
 */
public final class WindowsAppContainerWorkerSandboxBackend implements WorkerSandboxBackend {

    static final long MAX_JOB_MEMORY_BYTES = 8L * 1024L * 1024L * 1024L;
    static final int MAX_ACTIVE_PROCESSES = 128;
    static final int CPU_HARD_CAP = 8_000; // 80.00 percent; Job Object rates are 1/100th percent.

    private static final String RESOURCE = "/com/minos/runtime/windows-appcontainer-sandbox-v2.ps1";

    private final Path minosHome;
    private final Path powershell;
    private final Path launcher;

    public WindowsAppContainerWorkerSandboxBackend(Path minosHome, Path powershell) throws IOException {
        this.minosHome = Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize();
        this.powershell = regularExecutable(powershell, "powershell");
        this.launcher = installLauncher(this.minosHome);
    }

    public static Optional<WindowsAppContainerWorkerSandboxBackend> discover(Path minosHome) {
        Objects.requireNonNull(minosHome, "minosHome");
        if (WorkerSandboxQualification.currentPlatform() != WorkerSandboxQualification.Platform.WINDOWS) {
            return Optional.empty();
        }
        Optional<Path> shell = CommandLocator.find("powershell");
        if (shell.isEmpty()) {
            String systemRoot = System.getenv("SystemRoot");
            if (systemRoot != null && !systemRoot.isBlank()) {
                Path fallback = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
                if (Files.isRegularFile(fallback)) shell = Optional.of(fallback);
            }
        }
        if (shell.isEmpty()) return Optional.empty();
        try {
            return Optional.of(new WindowsAppContainerWorkerSandboxBackend(minosHome, shell.orElseThrow()));
        } catch (IOException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    @Override
    public String id() {
        return "windows-appcontainer-job-v1";
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
                        WorkerSandboxQualification.Platform.WINDOWS,
                        WorkerSandboxQualification.PlatformDisposition.QUALIFIED,
                        WorkerSandboxQualification.Platform.LINUX,
                        WorkerSandboxQualification.PlatformDisposition.NOT_APPLICABLE,
                        WorkerSandboxQualification.Platform.OTHER,
                        WorkerSandboxQualification.PlatformDisposition.NOT_APPLICABLE),
                List.of(
                        "WINDOWS_APPCONTAINER_EMPTY_CAPABILITY_SET",
                        "WINDOWS_CHILD_TOKEN_IS_APPCONTAINER_VERIFIED_BEFORE_RESUME",
                        "WINDOWS_EPHEMERAL_ACL_READ_WRITE_ROOTS",
                        "WINDOWS_JOB_KILL_ON_CLOSE",
                        "WINDOWS_JOB_ACTIVE_PROCESS_LIMIT",
                        "WINDOWS_JOB_MEMORY_LIMIT",
                        "WINDOWS_JOB_CPU_HARD_CAP",
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
            throw new IllegalStateException("Windows AppContainer backend is not qualified on this platform");
        }
        if (networkPolicy != WorkerNetworkPolicy.DENY) {
            throw new IllegalArgumentException(
                    "Windows AppContainer backend is a deny-network sandbox; ALLOW must use an explicitly non-isolated backend");
        }
        if (!(delegate instanceof ProcessIndexerExecutor processExecutor)) {
            throw new IllegalArgumentException(
                    "qualified OS sandbox requires ProcessIndexerExecutor so MINOS controls the actual provider process");
        }
        return processExecutor.executeSandboxed(
                request,
                (plan, runDirectory) -> sandboxPlan(plan, runDirectory));
    }

    IndexerProcessPlan sandboxPlan(IndexerProcessPlan plan, Path runDirectory) throws IOException {
        Objects.requireNonNull(plan, "plan");
        Path run = runDirectory.toRealPath();
        Path working = plan.workingDirectory().toRealPath();
        Path artifact = plan.generatedArtifact().toAbsolutePath().normalize();
        Path artifactParent = Objects.requireNonNull(artifact.getParent(), "artifact parent");
        Files.createDirectories(artifactParent);
        artifactParent = artifactParent.toRealPath();

        List<String> providerCommand = new ArrayList<>(plan.command());
        providerCommand.set(0, resolveExecutable(providerCommand.get(0)).toString());

        Set<Path> readRoots = new LinkedHashSet<>();
        Set<Path> writeRoots = new LinkedHashSet<>();
        addReadRoot(readRoots, resolveExecutable(providerCommand.get(0)).getParent());
        Path javaHome = Path.of(System.getProperty("java.home", ".")).toAbsolutePath().normalize();
        if (Files.isDirectory(javaHome)) addReadRoot(readRoots, javaHome);
        Path tools = minosHome.resolve("tools");
        if (Files.isDirectory(tools)) addReadRoot(readRoots, tools);
        for (int index = 1; index < providerCommand.size(); index++) {
            addExistingArgumentRoot(readRoots, providerCommand.get(index));
        }

        addWriteRoot(writeRoots, working);
        addWriteRoot(writeRoots, artifactParent);
        addWriteRoot(writeRoots, run);
        readRoots.removeIf(path -> writeRoots.stream().anyMatch(path::startsWith));

        Path planFile = run.resolve("windows-appcontainer-plan.txt").toAbsolutePath().normalize();
        writePlan(planFile, providerCommand, readRoots, writeRoots, working);

        List<String> wrapper = List.of(
                powershell.toString(),
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                launcher.toString(),
                "-Plan",
                planFile.toString());
        return new IndexerProcessPlan(
                wrapper,
                plan.workingDirectory(),
                plan.generatedArtifact(),
                plan.timeout(),
                plan.environment());
    }

    private static Path installLauncher(Path minosHome) throws IOException {
        Path directory = minosHome.resolve("sandbox").toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path target = directory.resolve("windows-appcontainer-sandbox-v2.ps1");
        try (InputStream input = WindowsAppContainerWorkerSandboxBackend.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IOException("embedded Windows AppContainer launcher is missing: " + RESOURCE);
            Path partial = Files.createTempFile(directory, ".windows-appcontainer-", ".ps1");
            try {
                Files.copy(input, partial, StandardCopyOption.REPLACE_EXISTING);
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(partial);
            }
        }
        return target;
    }

    private static void writePlan(
            Path target,
            List<String> command,
            Set<Path> readRoots,
            Set<Path> writeRoots,
            Path working
    ) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("working=" + encode(working.toString()));
        appendList(lines, "command", command);
        appendList(lines, "read", readRoots.stream().map(Path::toString).toList());
        appendList(lines, "write", writeRoots.stream().map(Path::toString).toList());
        lines.add("jobMemoryBytes=" + MAX_JOB_MEMORY_BYTES);
        lines.add("activeProcesses=" + MAX_ACTIVE_PROCESSES);
        lines.add("cpuRate=" + CPU_HARD_CAP);
        Files.write(target, lines, StandardCharsets.UTF_8);
    }

    private static void appendList(List<String> lines, String prefix, List<String> values) {
        lines.add(prefix + ".count=" + values.size());
        for (int index = 0; index < values.size(); index++) {
            lines.add(prefix + "." + index + "=" + encode(values.get(index)));
        }
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void addExistingArgumentRoot(Set<Path> roots, String value) {
        try {
            Path candidate = Path.of(value);
            if (!candidate.isAbsolute() || !Files.exists(candidate)) return;
            Path real = candidate.toRealPath();
            addReadRoot(roots, Files.isDirectory(real) ? real : real.getParent());
        } catch (IOException | RuntimeException ignored) {
            // Non-path provider arguments intentionally stay opaque.
        }
    }

    private static void addReadRoot(Set<Path> roots, Path value) {
        if (value != null && Files.isDirectory(value)) roots.add(value.toAbsolutePath().normalize());
    }

    private static void addWriteRoot(Set<Path> roots, Path value) {
        if (value != null && Files.isDirectory(value)) roots.add(value.toAbsolutePath().normalize());
    }

    private static Path resolveExecutable(String value) {
        Path candidate = Path.of(value);
        if (candidate.isAbsolute() && Files.isRegularFile(candidate)) return candidate.toAbsolutePath().normalize();
        return CommandLocator.find(value).orElseThrow(
                () -> new IllegalArgumentException("sandbox provider executable cannot be resolved: " + value));
    }

    private static Path regularExecutable(Path value, String label) {
        Path path = Objects.requireNonNull(value, label).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(label + " must be a regular file: " + path);
        }
        return path;
    }
}
