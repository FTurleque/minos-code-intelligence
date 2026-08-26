package com.minos.runtime;

import com.minos.io.FileTreeOperations;
import com.minos.io.PrivateLocalStorage;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.remote.DistributedIndexing.WorkerIsolation;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Windows worker sandbox backed by an AppContainer token and a Job Object.
 *
 * <p>ACL grants are scoped to exact provider files, MINOS-owned runtime roots and ephemeral write
 * roots. Arbitrary provider file arguments never cause their complete parent directory (notably a
 * user profile) to become readable. A recovery journal allows the next sandbox launch to reconcile
 * ACL/profile residue left by a forcibly terminated wrapper.</p>
 *
 * <p>The JVM currently running MINOS is never itself treated as a provider runtime root: it is the
 * host process, not something any {@code IndexerProcessPlan} invokes, and granting it unconditionally
 * would require mutating the DACL of wherever that JVM happens to live — including a Program
 * Files-installed JDK selected by an IDE run configuration, which a non-elevated standard user cannot
 * do. Every read root this backend grants is either a MINOS-managed root it owns, an ephemeral
 * write/run directory it just created, or an explicitly declared toolchain-home value; any candidate
 * this process cannot grant without elevation fails the plan closed instead of reaching {@code
 * icacls}.</p>
 *
 * <p>Windows also gives every AppContainer its own implicit file/registry storage. That storage is
 * not one of the ACL roots visible to the Java-side quota supervisor, so the launcher supervises it
 * inside the same Job Object boundary. The global provider budget is split between the explicit
 * MINOS roots and this private AppContainer storage; the two independent ceilings therefore still
 * sum to at most {@link ProviderWriteQuota#DEFAULT}.</p>
 */
public final class WindowsAppContainerWorkerSandboxBackend implements WorkerSandboxBackend {

    static final long MAX_JOB_MEMORY_BYTES = 8L * 1024L * 1024L * 1024L;
    static final int MAX_ACTIVE_PROCESSES = 128;
    static final int CPU_HARD_CAP = 8_000;
    /** Aggregate job CPU seconds granted per wall-clock second of the provider timeout. */
    static final long JOB_CPU_SECONDS_PER_WALL_CLOCK_SECOND = 8L;

    /** Reserved part of the global write budget for implicit AppContainer file/registry storage. */
    static final long PRIVATE_STORAGE_MAX_BYTES = 1L * 1024L * 1024L * 1024L;
    static final long PRIVATE_STORAGE_MAX_ENTRIES = 50_000L;
    static final long PRIVATE_STORAGE_SAMPLE_MILLIS =
            ProviderWriteQuota.DEFAULT_SAMPLE_PERIOD.toMillis();
    static final ProviderWriteQuota EXPLICIT_ROOT_WRITE_QUOTA = new ProviderWriteQuota(
            ProviderWriteQuota.DEFAULT_MAX_BYTES - PRIVATE_STORAGE_MAX_BYTES,
            ProviderWriteQuota.DEFAULT_MAX_ENTRIES - PRIVATE_STORAGE_MAX_ENTRIES,
            ProviderWriteQuota.DEFAULT_SAMPLE_PERIOD);

    private static final String SANDBOX_DIRECTORY = "sandbox";
    private static final String LAUNCHER_SCRIPT_NAME = "windows-appcontainer-sandbox-v4.ps1";
    private static final String RESOURCE = "/com/minos/runtime/" + LAUNCHER_SCRIPT_NAME;
    private static final Map<Path, Boolean> CAPABILITY_PROBE_CACHE = new ConcurrentHashMap<>();

    private static final System.Logger LOGGER =
            System.getLogger(WindowsAppContainerWorkerSandboxBackend.class.getName());

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
            Path home = minosHome.toAbsolutePath().normalize();
            WindowsAppContainerWorkerSandboxBackend candidate =
                    new WindowsAppContainerWorkerSandboxBackend(home, shell.orElseThrow());
            boolean qualified = CAPABILITY_PROBE_CACHE.computeIfAbsent(home, ignored -> candidate.probeOsIsolation());
            if (!qualified) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "MINOS Windows AppContainer worker sandbox is unavailable: the real AppContainer/Job Object "
                                + "capability probe failed; managed provider execution stays fail-closed");
                return Optional.empty();
            }
            return Optional.of(candidate);
        } catch (IOException | IllegalArgumentException exception) {
            // Not just "PowerShell missing": this can also mean the launcher could not be installed
            // as owner-only (e.g. the private-storage filesystem could not enforce or verify
            // ownership) or another environmental failure. WorkerSandboxBackends.strongestAvailable()
            // silently falls back to the weaker native-ephemeral tier for untrusted provider code
            // when this returns empty, so the degradation must be observable, not silent.
            LOGGER.log(System.Logger.Level.WARNING,
                    "MINOS Windows AppContainer worker sandbox is unavailable, falling back to a weaker "
                            + "sandbox tier", exception);
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
                        "WINDOWS_APPCONTAINER_PRIVATE_FILE_AND_REGISTRY_STORAGE_SUPERVISED",
                        "WINDOWS_RUNTIME_CAPABILITY_PROBE_REQUIRED",
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
                        "JOB_OBJECT_LIMIT_KILL_ON_CLOSE",
                        "TERMINATE_JOB_OBJECT_ON_EVERY_EXIT_PATH",
                        "MINOS_PROVIDER_WRITE_QUOTA_SUPERVISOR",
                        "MINOS_APPCONTAINER_PRIVATE_STORAGE_QUOTA_SUPERVISOR",
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
        if (!(delegate instanceof ProcessSandboxCapableIndexerExecutor processExecutor)) {
            throw new IllegalArgumentException(
                    "qualified OS sandbox requires an executor with explicit process-sandbox capability");
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
                        return Optional.of(EXPLICIT_ROOT_WRITE_QUOTA);
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

        Map<String, String> providerEnvironment = ProviderProcessEnvironment.sanitize(
                new ProcessBuilder().environment(),
                plan.environment());

        Set<Path> readRoots = new LinkedHashSet<>();
        Set<Path> readFiles = new LinkedHashSet<>();
        Set<Path> writeRoots = new LinkedHashSet<>();
        Path tools = minosHome.resolve("tools").toAbsolutePath().normalize();
        // The JVM running MINOS itself (System.getProperty("java.home")) is never added here: it is
        // the host process, not a provider runtime, and no IndexerProcessPlan ever invokes it. Adding
        // it unconditionally used to grant AppContainer ACL access to whatever JDK happens to be
        // running MINOS — including a Program Files-installed JDK selected by an IntelliJ run
        // configuration — which fails without administrator rights, since a non-owner cannot mutate
        // a Program Files object's DACL. Every runtime a provider actually needs is either a
        // MINOS-managed root under `tools` or an explicitly declared toolchain-home below.
        addExecutableAccess(readRoots, readFiles, executable, tools);
        for (int index = 1; index < providerCommand.size(); index++) {
            addExistingArgumentAccess(readRoots, readFiles, providerCommand.get(index), tools);
        }
        // A toolchain-home variable (notably JAVA_HOME for a project JDK distinct from MINOS' own
        // bundled runtime) names a runtime root the sandboxed provider must read; without it
        // scip-java cannot even probe its own java.exe. Only these specific keys grant a root:
        // the sanitized environment also carries USERPROFILE/APPDATA/ProgramFiles-style values, and
        // granting those would make a whole profile readable — exactly what this backend forbids.
        for (Map.Entry<String, String> entry : providerEnvironment.entrySet()) {
            if (isToolchainHomeKey(entry.getKey())) {
                addToolchainHomeReadRoot(readRoots, entry.getValue());
            }
        }

        addWriteRoot(writeRoots, working);
        addWriteRoot(writeRoots, artifactParent);
        addWriteRoot(writeRoots, run);
        readRoots.removeIf(path -> writeRoots.stream().anyMatch(path::startsWith));
        readFiles.removeIf(path -> writeRoots.stream().anyMatch(path::startsWith));

        Path planFile = run.resolve("windows-appcontainer-plan.txt").toAbsolutePath().normalize();
        Path recovery = minosHome.resolve(SANDBOX_DIRECTORY)
                .resolve("appcontainer-recovery").toAbsolutePath().normalize();
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

    /**
     * Executes the actual packaged launcher with a harmless suspended child. A host is never
     * reported as qualified merely because PowerShell and the launcher file exist: profile creation,
     * AppContainer token creation, Job Object configuration/read-back, assignment and resume must
     * all succeed once per MINOS home/JVM.
     */
    private boolean probeOsIsolation() {
        Path probeRoot = minosHome.resolve(SANDBOX_DIRECTORY)
                .resolve("appcontainer-probe-" + Long.toHexString(System.nanoTime()))
                .toAbsolutePath().normalize();
        try {
            Path working = Files.createDirectories(probeRoot.resolve("working"));
            Path run = Files.createDirectories(probeRoot.resolve("run"));
            IndexerProcessPlan original = new IndexerProcessPlan(
                    List.of(
                            powershell.toString(),
                            "-NoLogo",
                            "-NoProfile",
                            "-NonInteractive",
                            "-Command",
                            "exit 0"),
                    working,
                    Map.of(),
                    run.resolve("probe.scip"),
                    Duration.ofSeconds(15));
            IndexerProcessPlan sandboxed = sandboxPlan(original, run, WorkerNetworkPolicy.DENY);
            Process process = new ProcessBuilder(sandboxed.command())
                    .directory(working.toFile())
                    .redirectErrorStream(true)
                    .start();
            if (!awaitProbeCompletion(process, Duration.ofSeconds(15))) {
                ProcessTreeTermination.terminateTree(process, Duration.ZERO, Duration.ofSeconds(5));
                LOGGER.log(System.Logger.Level.WARNING, "MINOS Windows AppContainer capability probe timed out");
                return false;
            }
            return probeExitedCleanly(process);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException exception) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "MINOS Windows AppContainer capability probe could not complete", exception);
            return false;
        } finally {
            try {
                FileTreeOperations.deleteRecursively(probeRoot);
            } catch (IOException ignored) {
                // Disposable probe residue is beneath MINOS_HOME/sandbox and can be reclaimed later.
            }
        }
    }

    private static boolean awaitProbeCompletion(Process process, Duration timeout) throws InterruptedException {
        try {
            process.onExit().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException timeoutException) {
            return false;
        } catch (ExecutionException failure) {
            throw new IllegalStateException("AppContainer capability probe completion failed", failure.getCause());
        }
    }

    private static boolean probeExitedCleanly(Process process) throws IOException {
        String output = new String(process.getInputStream().readNBytes(8192), StandardCharsets.UTF_8).trim();
        if (process.exitValue() == 0) return true;
        LOGGER.log(System.Logger.Level.WARNING,
                "MINOS Windows AppContainer capability probe failed (exit=" + process.exitValue() + "): " + output);
        return false;
    }

    /**
     * Bounded tolerance for a transient replace failure while publishing the launcher (e.g. a
     * real-time antivirus scan briefly holding the just-written or the previous script). This is
     * MINOS' own trusted, MINOS-authored script content, not attacker-controlled input, so
     * retrying a plain filesystem replace here carries none of the containment implications a
     * provider-controlled path would.
     */
    private static final int LAUNCHER_INSTALL_RETRY_ATTEMPTS = 5;
    private static final long LAUNCHER_INSTALL_RETRY_DELAY_MILLIS = 50L;

    private static Path installLauncher(Path minosHome) throws IOException {
        Path directory = minosHome.resolve(SANDBOX_DIRECTORY).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path target = directory.resolve(LAUNCHER_SCRIPT_NAME);
        // Assembled from its template and the shared Win32 fragments, then published as one
        // self-contained file: the script that executes still has a single hash and no include path.
        String launcher = WindowsContainmentScript.assemble(LAUNCHER_SCRIPT_NAME);
        Path partial = PrivateLocalStorage.createPrivateTempFile(directory, ".windows-appcontainer-", ".ps1");
        try {
            Files.writeString(partial, launcher, StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            replaceWithRetry(partial, target);
        } finally {
            Files.deleteIfExists(partial);
        }
        return target;
    }

    private static void replaceWithRetry(Path source, Path target) throws IOException {
        for (int attempt = 1; attempt <= LAUNCHER_INSTALL_RETRY_ATTEMPTS; attempt++) {
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (FileSystemException failure) {
                if (attempt == LAUNCHER_INSTALL_RETRY_ATTEMPTS) {
                    throw failure;
                }
                try {
                    Thread.sleep(LAUNCHER_INSTALL_RETRY_DELAY_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw failure;
                }
            }
        }
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
        lines.add("privateStorageMaxBytes=" + PRIVATE_STORAGE_MAX_BYTES);
        lines.add("privateStorageMaxEntries=" + PRIVATE_STORAGE_MAX_ENTRIES);
        lines.add("privateStorageSampleMillis=" + PRIVATE_STORAGE_SAMPLE_MILLIS);
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
            Path tools
    ) throws IOException {
        Path real = executable.toRealPath();
        if (isWindowsSystemRoot(real)) return;
        if (real.startsWith(tools)) {
            addReadRoot(roots, managedRuntimeRoot(real, tools));
        } else {
            addReadFile(files, real);
        }
    }

    private static void addExistingArgumentAccess(
            Set<Path> roots,
            Set<Path> files,
            String value,
            Path tools
    ) {
        try {
            Path candidate = Path.of(value);
            if (!candidate.isAbsolute() || !Files.exists(candidate)) return;
            Path real = candidate.toRealPath();
            if (isWindowsSystemRoot(real)) return;
            if (real.startsWith(tools)) {
                addReadRoot(roots, managedRuntimeRoot(real, tools));
            } else if (Files.isRegularFile(real)) {
                addReadFile(files, real);
            }
        } catch (IOException | InvalidPathException ignored) {
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
        requireAclGrantable(normalized);
        roots.add(normalized);
    }

    private static void addReadFile(Set<Path> files, Path value) {
        if (value == null || !Files.isRegularFile(value)) return;
        Path normalized = value.toAbsolutePath().normalize();
        if (isWindowsSystemRoot(normalized)) return;
        requireAclGrantable(normalized);
        files.add(normalized);
    }

    /**
     * Fails closed, before any PowerShell/icacls invocation, when the current process cannot grant
     * an AppContainer ACE on {@code path} without elevation. Granting a temporary ACE always requires
     * WRITE_DAC on the target; a standard user has that on anything MINOS itself created (its own
     * managed tools, working/run directories) but never on a Program Files- or SystemRoot-owned
     * object it merely reads. Probing by round-tripping the real ACL (read it, write the same content
     * back) is the only way to ask the OS the exact question icacls itself would answer, without
     * duplicating Windows' access-check logic in Java.
     */
    static void requireAclGrantable(Path path) {
        if (isAclGrantable(path)) return;
        throw new IllegalStateException(
                "Provider runtime is not compatible with the non-elevated Windows sandbox: cannot grant "
                        + "AppContainer read access to " + path + " without administrator privileges. Move this "
                        + "dependency under a user-owned location (for example under %LOCALAPPDATA%), point the "
                        + "relevant toolchain variable at a user-owned installation instead, or use a "
                        + "MINOS-managed toolchain.");
    }

    static boolean isAclGrantable(Path path) {
        try {
            AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (view == null) return false;
            List<AclEntry> acl = view.getAcl();
            view.setAcl(acl);
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException notGrantable) {
            return false;
        }
    }

    /** Environment keys whose value legitimately names a toolchain root the provider reads. */
    private static final Set<String> TOOLCHAIN_HOME_KEYS = Set.of(
            "JAVA_HOME",
            "JDK_HOME",
            "DOTNET_ROOT",
            "CARGO_HOME",
            "RUSTUP_HOME",
            "COURSIER_CACHE");

    /** Broad locations a toolchain-home grant must never cover, even transitively. */
    private static final List<String> OVER_BROAD_GRANT_KEYS = List.of(
            "USERPROFILE",
            "HOME",
            "PUBLIC",
            "APPDATA",
            "LOCALAPPDATA",
            "ProgramFiles",
            "ProgramFiles(x86)",
            "ProgramW6432",
            "ProgramData",
            "ALLUSERSPROFILE",
            "SystemDrive");

    private static boolean isToolchainHomeKey(String candidate) {
        return candidate != null && TOOLCHAIN_HOME_KEYS.stream().anyMatch(key -> key.equalsIgnoreCase(candidate));
    }

    /**
     * Grants read access to an existing toolchain root, never to a profile-wide or system location.
     * Delegates the actual grant to {@link #addReadRoot}, which fails closed (rather than silently
     * granting nothing) when the root cannot be ACL'd without elevation — e.g. a project JDK that
     * itself lives under Program Files.
     */
    private static void addToolchainHomeReadRoot(Set<Path> roots, String value) {
        if (value == null || value.isBlank()) return;
        Path candidate;
        try {
            candidate = Path.of(value);
        } catch (InvalidPathException notAPath) {
            return;
        }
        if (!candidate.isAbsolute()) return;
        Path real;
        try {
            real = candidate.toRealPath();
        } catch (IOException unresolvable) {
            return;
        }
        if (!Files.isDirectory(real)) return;
        if (isWindowsSystemRoot(real) || isOverBroadGrant(real)) return;
        addReadRoot(roots, real);
    }

    /**
     * Rejects a directory whose grant would cover a drive root, {@code C:\Users}-style container or
     * any well-known profile/system location, so a manipulated toolchain variable cannot widen the
     * sandbox beyond the toolchain it is supposed to name.
     */
    private static boolean isOverBroadGrant(Path directory) {
        if (directory.getNameCount() < 2) return true;
        for (String key : OVER_BROAD_GRANT_KEYS) {
            String raw = System.getenv(key);
            if (raw == null || raw.isBlank()) continue;
            try {
                if (Path.of(raw).toAbsolutePath().normalize().startsWith(directory)) return true;
            } catch (RuntimeException ignored) {
                // An unparseable broad location cannot be compared and constrains nothing.
            }
        }
        return false;
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
