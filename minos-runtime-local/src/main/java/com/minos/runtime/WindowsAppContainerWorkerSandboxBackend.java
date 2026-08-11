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
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Windows worker sandbox backed by an AppContainer token and a Job Object.
 *
 * <p>ACL grants are scoped to exact provider files, MINOS-owned runtime roots and ephemeral write
 * roots. Arbitrary provider file arguments never cause their complete parent directory (notably a
 * user profile) to become readable. A recovery journal allows the next sandbox launch to reconcile
 * ACL/profile residue left by a forcibly terminated wrapper.</p>
 */
public final class WindowsAppContainerWorkerSandboxBackend implements WorkerSandboxBackend {

    static final long MAX_JOB_MEMORY_BYTES = 8L * 1024L * 1024L * 1024L;
    static final int MAX_ACTIVE_PROCESSES = 128;
    static final int CPU_HARD_CAP = 8_000;
    /** Aggregate job CPU seconds granted per wall-clock second of the provider timeout. */
    static final long JOB_CPU_SECONDS_PER_WALL_CLOCK_SECOND = 8L;

    private static final String LAUNCHER = "windows-appcontainer-sandbox-v4.ps1";
    private static final String RESOURCE = "/com/minos/runtime/" + LAUNCHER;

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
        Optional<Path> shell = CommandLocator.windowsPowerShell();
        if (shell.isEmpty()) return Optional.empty();
        try {
            return Optional.of(new WindowsAppContainerWorkerSandboxBackend(minosHome, shell.orElseThrow()));
        } catch (IOException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    @Override
    public String id() {
        return "windows-appcontainer-job-v3";
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
                containment(),
                Map.of(
                        WorkerSandboxQualification.Platform.WINDOWS,
                        WorkerSandboxQualification.PlatformDisposition.QUALIFIED,
                        WorkerSandboxQualification.Platform.LINUX,
                        WorkerSandboxQualification.PlatformDisposition.NOT_APPLICABLE,
                        WorkerSandboxQualification.Platform.OTHER,
                        WorkerSandboxQualification.PlatformDisposition.NOT_APPLICABLE),
                List.of(
                        "WINDOWS_DENY_APPCONTAINER_EMPTY_CAPABILITY_SET",
                        "WINDOWS_ALLOW_APPCONTAINER_INTERNET_CLIENT_CAPABILITY_ONLY",
                        "WINDOWS_CHILD_TOKEN_IS_APPCONTAINER_VERIFIED_BEFORE_RESUME",
                        "WINDOWS_PROVIDER_ENVIRONMENT_EXPLICIT_ALLOWLIST",
                        "WINDOWS_EXACT_FILE_PROVIDER_ACL",
                        "WINDOWS_MINOS_RUNTIME_ROOT_ACL_ONLY",
                        "WINDOWS_STALE_ACL_PROFILE_RECONCILIATION",
                        "WINDOWS_SYSTEM_ROOT_ACL_UNMODIFIED",
                        "WINDOWS_JOB_CONFIGURED_BEFORE_THE_CONTAINED_PROCESS_EXISTS",
                        "WINDOWS_JOB_ASSIGNED_WHILE_SUSPENDED_AND_MEMBERSHIP_VERIFIED",
                        "WINDOWS_JOB_BREAKAWAY_PROHIBITED",
                        "WINDOWS_JOB_LIMITS_READ_BACK_FROM_THE_KERNEL",
                        "WINDOWS_JOB_KILL_ON_CLOSE",
                        "WINDOWS_JOB_TERMINATED_ON_EVERY_EXIT_PATH",
                        "WINDOWS_JOB_ACTIVE_PROCESS_LIMIT",
                        "WINDOWS_JOB_MEMORY_LIMIT",
                        "WINDOWS_JOB_CPU_HARD_CAP_AND_AGGREGATE_JOB_TIME_LIMIT",
                        "MINOS_SUPERVISED_PROVIDER_WRITE_QUOTA_BYTES_AND_ENTRIES",
                        "MINOS_WALL_CLOCK_TIMEOUT_AND_RUN_RESIDUE_RECLAMATION"));
    }

    /** Aggregate containment the AppContainer Job Object really enforces. */
    static WorkerResourceContainment containment() {
        return new WorkerResourceContainment(
                "windows-appcontainer-job-object",
                WorkerResourceContainment.Disposition.OS_ENFORCED,
                WorkerResourceContainment.Disposition.OS_ENFORCED,
                WorkerResourceContainment.Disposition.OS_ENFORCED,
                WorkerResourceContainment.Disposition.SUPERVISED_HARD_KILL,
                WorkerResourceContainment.Disposition.SUPERVISED_HARD_KILL,
                WorkerResourceContainment.Disposition.SUPERVISED_HARD_KILL,
                WorkerResourceContainment.Disposition.OS_ENFORCED,
                WorkerResourceContainment.Disposition.SUPERVISED_HARD_KILL,
                List.of(
                        "JOB_OBJECT_LIMIT_ACTIVE_PROCESS",
                        "JOB_OBJECT_LIMIT_JOB_MEMORY",
                        "JOB_OBJECT_CPU_RATE_CONTROL_HARD_CAP",
                        "JOB_OBJECT_LIMIT_JOB_TIME",
                        "JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE",
                        "TERMINATE_JOB_OBJECT_ON_EVERY_EXIT_PATH",
                        "MINOS_PROVIDER_WRITE_QUOTA_SUPERVISOR",
                        "MINOS_WALL_CLOCK_TIMEOUT",
                        "MINOS_RUN_RESIDUE_RECLAMATION"));
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
        if (!(delegate instanceof ProcessIndexerExecutor processExecutor)) {
            throw new IllegalArgumentException(
                    "qualified OS sandbox requires ProcessIndexerExecutor so MINOS controls the actual provider process");
        }
        return processExecutor.executeSandboxed(
                request,
                new ProcessIndexerExecutor.ProcessPlanTransformer() {
                    @Override
                    public IndexerProcessPlan transform(IndexerProcessPlan plan, Path runDirectory) throws Exception {
                        return sandboxPlan(plan, runDirectory, networkPolicy);
                    }

                    @Override
                    public boolean trustedLauncherRequiresParentEnvironment() {
                        return true;
                    }

                    @Override
                    public Optional<ProviderWriteQuota> providerWriteQuota() {
                        return Optional.of(ProviderWriteQuota.DEFAULT);
                    }
                });
    }

    IndexerProcessPlan sandboxPlan(IndexerProcessPlan plan, Path runDirectory) throws IOException {
        return sandboxPlan(plan, runDirectory, WorkerNetworkPolicy.DENY);
    }

    IndexerProcessPlan sandboxPlan(
            IndexerProcessPlan plan,
            Path runDirectory,
            WorkerNetworkPolicy networkPolicy
    ) throws IOException {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(networkPolicy, "networkPolicy");
        Path run = runDirectory.toRealPath();
        Path working = plan.workingDirectory().toRealPath();
        Path artifact = plan.generatedArtifact().toAbsolutePath().normalize();
        Path artifactParent = Objects.requireNonNull(artifact.getParent(), "artifact parent");
        Files.createDirectories(artifactParent);
        artifactParent = artifactParent.toRealPath();

        List<String> providerCommand = new ArrayList<>(plan.command());
        Path executable = resolveExecutable(providerCommand.get(0));
        providerCommand.set(0, executable.toString());

        Set<Path> readRoots = new LinkedHashSet<>();
        Set<Path> readFiles = new LinkedHashSet<>();
        Set<Path> writeRoots = new LinkedHashSet<>();
        Path javaHome = Path.of(System.getProperty("java.home", ".")).toAbsolutePath().normalize();
        Path tools = minosHome.resolve("tools").toAbsolutePath().normalize();
        if (Files.isDirectory(javaHome)) addReadRoot(readRoots, javaHome);
        addExecutableAccess(readRoots, readFiles, executable, tools, javaHome);
        for (int index = 1; index < providerCommand.size(); index++) {
            addExistingArgumentAccess(readRoots, readFiles, providerCommand.get(index), tools, javaHome);
        }

        addWriteRoot(writeRoots, working);
        addWriteRoot(writeRoots, artifactParent);
        addWriteRoot(writeRoots, run);
        readRoots.removeIf(path -> writeRoots.stream().anyMatch(path::startsWith));
        readFiles.removeIf(path -> writeRoots.stream().anyMatch(path::startsWith));

        Map<String, String> providerEnvironment = ProviderProcessEnvironment.sanitize(
                new ProcessBuilder().environment(),
                plan.environment());
        Path planFile = run.resolve("windows-appcontainer-plan.txt").toAbsolutePath().normalize();
        Path recovery = minosHome.resolve("sandbox").resolve("appcontainer-recovery").toAbsolutePath().normalize();
        Files.createDirectories(recovery);
        writePlan(
                planFile,
                providerCommand,
                providerEnvironment,
                readRoots,
                readFiles,
                writeRoots,
                working,
                recovery,
                networkPolicy,
                jobCpuSeconds(plan.timeout()));

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
                Map.of(),
                plan.generatedArtifact(),
                plan.timeout());
    }

    private static Path installLauncher(Path minosHome) throws IOException {
        Path directory = minosHome.resolve("sandbox").toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path target = directory.resolve(LAUNCHER);
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
            Map<String, String> environment,
            Set<Path> readRoots,
            Set<Path> readFiles,
            Set<Path> writeRoots,
            Path working,
            Path recoveryDirectory,
            WorkerNetworkPolicy networkPolicy,
            long jobCpuSeconds
    ) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("working=" + encode(working.toString()));
        lines.add("recovery=" + encode(recoveryDirectory.toString()));
        lines.add("networkPolicy=" + networkPolicy.name());
        appendList(lines, "command", command);
        appendEnvironment(lines, environment);
        appendList(lines, "read", readRoots.stream().map(Path::toString).toList());
        appendList(lines, "readFile", readFiles.stream().map(Path::toString).toList());
        appendList(lines, "write", writeRoots.stream().map(Path::toString).toList());
        lines.add("jobMemoryBytes=" + MAX_JOB_MEMORY_BYTES);
        lines.add("activeProcesses=" + MAX_ACTIVE_PROCESSES);
        lines.add("cpuRate=" + CPU_HARD_CAP);
        lines.add("jobCpuSeconds=" + jobCpuSeconds);
        Files.write(target, lines, StandardCharsets.UTF_8);
    }

    /** Aggregate CPU seconds the whole job may burn, derived from the MINOS wall-clock timeout. */
    static long jobCpuSeconds(Duration timeout) {
        long seconds = Math.max(1L, timeout.toSeconds());
        long granted = seconds > Long.MAX_VALUE / JOB_CPU_SECONDS_PER_WALL_CLOCK_SECOND
                ? Long.MAX_VALUE / JOB_CPU_SECONDS_PER_WALL_CLOCK_SECOND
                : seconds * JOB_CPU_SECONDS_PER_WALL_CLOCK_SECOND;
        return Math.min(granted, 86_400L);
    }

    private static void appendEnvironment(List<String> lines, Map<String, String> environment) {
        List<Map.Entry<String, String>> entries = environment.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(String::toLowerCase)))
                .toList();
        lines.add("environment.count=" + entries.size());
        for (int index = 0; index < entries.size(); index++) {
            Map.Entry<String, String> entry = entries.get(index);
            lines.add("environment." + index + ".key=" + encode(entry.getKey()));
            lines.add("environment." + index + ".value=" + encode(entry.getValue()));
        }
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

    private static void addExecutableAccess(
            Set<Path> roots,
            Set<Path> files,
            Path executable,
            Path tools,
            Path javaHome
    ) throws IOException {
        Path real = executable.toRealPath();
        if (isWindowsSystemRoot(real)) return;
        if (real.startsWith(tools)) {
            addReadRoot(roots, managedRuntimeRoot(real, tools));
        } else if (real.startsWith(javaHome)) {
            addReadRoot(roots, javaHome);
        } else {
            addReadFile(files, real);
        }
    }

    private static void addExistingArgumentAccess(
            Set<Path> roots,
            Set<Path> files,
            String value,
            Path tools,
            Path javaHome
    ) {
        try {
            Path candidate = Path.of(value);
            if (!candidate.isAbsolute() || !Files.exists(candidate)) return;
            Path real = candidate.toRealPath();
            if (isWindowsSystemRoot(real)) return;
            if (real.startsWith(tools)) {
                addReadRoot(roots, managedRuntimeRoot(real, tools));
            } else if (real.startsWith(javaHome)) {
                addReadRoot(roots, javaHome);
            } else if (Files.isRegularFile(real)) {
                addReadFile(files, real);
            }
        } catch (IOException | RuntimeException ignored) {
            // Non-path provider arguments intentionally stay opaque.
        }
    }

    private static Path managedRuntimeRoot(Path value, Path tools) {
        Path normalizedTools = tools.toAbsolutePath().normalize();
        Path normalized = value.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedTools)) return normalized;
        Path relative = normalizedTools.relativize(normalized);
        if (relative.getNameCount() < 2) {
            throw new IllegalArgumentException("managed provider path does not identify provider/version: " + value);
        }
        return normalizedTools.resolve(relative.subpath(0, 2)).normalize();
    }

    private static void addReadRoot(Set<Path> roots, Path value) {
        if (value == null || !Files.isDirectory(value)) return;
        Path normalized = value.toAbsolutePath().normalize();
        if (isWindowsSystemRoot(normalized)) return;
        roots.add(normalized);
    }

    private static void addReadFile(Set<Path> files, Path value) {
        if (value == null || !Files.isRegularFile(value)) return;
        Path normalized = value.toAbsolutePath().normalize();
        if (isWindowsSystemRoot(normalized)) return;
        files.add(normalized);
    }

    private static boolean isWindowsSystemRoot(Path value) {
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null || systemRoot.isBlank()) return false;
        Path root = Path.of(systemRoot).toAbsolutePath().normalize();
        return value.startsWith(root);
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
