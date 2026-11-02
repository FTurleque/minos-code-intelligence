package com.minos.runtime.local.sandbox;

import com.minos.runtime.local.process.CommandLocator;
import com.minos.runtime.local.process.IndexerProcessPlan;
import com.minos.runtime.local.process.ProcessIndexerExecutor;
import com.minos.runtime.local.process.ProcessSandboxCapableIndexerExecutor;
import com.minos.runtime.local.process.ProviderProcessEnvironment;
import com.minos.runtime.local.workspace.ProviderWriteQuota;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import static com.minos.diagnostics.PublicErrorMessages.leafName;

/**
 * Linux worker sandbox backed by bubblewrap namespaces, a cgroup v2 job boundary and prlimit.
 *
 * <p>The host root is never exposed wholesale. System runtime roots and the exact managed provider
 * runtime needed by the command are mounted read-only; arbitrary absolute files are mounted as
 * individual files instead of exposing their parent directories. The JDK named by {@code JAVA_HOME} or
 * {@code JDK_HOME} in the provider environment is mounted read-only as a whole real root, with only the
 * exact allow-listed Java configuration under /etc its links reach; an over-broad root is refused before
 * launch. The workspace, artifact and MINOS
 * run roots are writable. Read-only roots a plan declares are checked again and mounted exactly, after the
 * writable roots; files a plan masks are presented empty. DENY keeps bubblewrap's isolated network namespace; ALLOW explicitly
 * shares the host network namespace.</p>
 *
 * <p>Resource containment is aggregate and lives in {@link LinuxCgroupJob}: {@code memory.max},
 * {@code memory.swap.max}, {@code pids.max}, {@code cpu.max} and {@code cgroup.kill} apply to the
 * whole provider process tree. The {@code prlimit} values kept below are per-process defence in
 * depth only — a provider multiplies them by forking, so they are never presented as an aggregate
 * guarantee. Without a delegated cgroup v2 boundary this backend does not exist and untrusted
 * execution is refused before the provider starts.</p>
 */
public final class LinuxBubblewrapWorkerSandboxBackend implements OsEnforcedWorkerSandboxBackend {

    private static final System.Logger LOGGER =
            System.getLogger(LinuxBubblewrapWorkerSandboxBackend.class.getName());

    /** Aggregate memory of the whole provider tree, enforced by {@code memory.max}. */
    static final long MAX_JOB_MEMORY_BYTES = 8L * 1024L * 1024L * 1024L;
    /** Per-process address space; defence in depth on top of the aggregate cgroup limit. */
    static final long MAX_ADDRESS_SPACE_BYTES = MAX_JOB_MEMORY_BYTES;
    /**
     * Aggregate process/thread count of the whole provider tree, enforced by {@code pids.max} only.
     * No {@code prlimit --nproc}: RLIMIT_NPROC counts every task of the real user, host threads
     * included, so it bounds neither a process nor the provider tree and would refuse the launch of
     * an account that already runs that many threads (MINOS-AUD-H17).
     */
    static final int MAX_PROCESSES = 128;
    static final int MAX_OPEN_FILES = 2048;
    /** Aggregate CPU bandwidth per {@link LinuxCgroupJob#CPU_PERIOD_MICROS}, enforced by {@code cpu.max}. */
    static final long MAX_CPU_MICROS_PER_PERIOD = 8L * LinuxCgroupJob.CPU_PERIOD_MICROS;

    private static final String PROC = "/proc";
    static final String RO_BIND = "--ro-bind";
    private static final String TOOLS_DIRECTORY = "tools";

    private static final Path POSIX_SHELL = Path.of("/bin/sh");

    /** Provider environment keys whose value names a JDK the provider runs or hands to its build. */
    static final List<String> TOOLCHAIN_HOME_KEYS = List.of("JAVA_HOME", "JDK_HOME");

    private static final Path ETC = Path.of("/etc");

    /**
     * Public certificate stores a JDK links its {@code cacerts} into (Debian/Ubuntu, Fedora/RHEL). With
     * first-level {@code /etc/java*} directories they are the only /etc locations a toolchain link may reach.
     */
    static final List<Path> ETC_LINK_TARGET_ROOTS = List.of(
            Path.of("/etc/ssl/certs"),
            Path.of("/etc/pki/java"),
            Path.of("/etc/pki/ca-trust/extracted"));

    /** Bounds of the link scan of a toolchain root; a JDK has a few thousand entries. */
    static final int MAX_TOOLCHAIN_SCAN_DEPTH = 16;
    static final int MAX_TOOLCHAIN_SCAN_ENTRIES = 50_000;

    private final Path minosHome;
    private final Path bubblewrap;
    private final Path prlimit;
    private final Path shell;

    public LinuxBubblewrapWorkerSandboxBackend(Path bubblewrap, Path prlimit) {
        this(null, bubblewrap, prlimit);
    }

    LinuxBubblewrapWorkerSandboxBackend(Path minosHome, Path bubblewrap, Path prlimit) {
        this(minosHome, bubblewrap, prlimit, POSIX_SHELL);
    }

    LinuxBubblewrapWorkerSandboxBackend(Path minosHome, Path bubblewrap, Path prlimit, Path shell) {
        this.minosHome = minosHome == null ? null : minosHome.toAbsolutePath().normalize();
        this.bubblewrap = regularExecutable(bubblewrap, "bubblewrap");
        this.prlimit = regularExecutable(prlimit, "prlimit");
        this.shell = Objects.requireNonNull(shell, "shell").toAbsolutePath().normalize();
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
        if (LinuxCgroupJob.delegatedRoot().isEmpty()) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "MINOS Linux worker sandbox is unqualified: no delegated cgroup v2 job boundary (set "
                            + LinuxCgroupJob.ROOT_ENVIRONMENT_VARIABLE + " or run MINOS in a delegated cgroup); "
                            + "untrusted provider execution stays fail-closed");
            return Optional.empty();
        }
        LinuxBubblewrapWorkerSandboxBackend candidate =
                new LinuxBubblewrapWorkerSandboxBackend(minosHome, bwrap.orElseThrow(), limits.orElseThrow());
        return candidate.probeOsIsolation() ? Optional.of(candidate) : Optional.empty();
    }

    @Override
    public String id() {
        return "linux-bubblewrap-cgroup2-v5";
    }

    @Override
    public WorkerSandboxQualification qualification() {
        boolean job = LinuxCgroupJob.delegatedRoot().isPresent();
        return new WorkerSandboxQualification(
                id(),
                isolation(),
                networkGuarantee(),
                WorkerSandboxQualification.NetworkDenyDisposition.QUALIFIED,
                job
                        ? WorkerSandboxQualification.TrustDisposition.UNTRUSTED_CODE_SUPPORTED
                        : WorkerSandboxQualification.TrustDisposition.UNTRUSTED_CODE_UNSUPPORTED,
                job ? containment() : WorkerResourceContainment.none(id()),
                Map.of(
                        WorkerSandboxQualification.Platform.LINUX,
                        job
                                ? WorkerSandboxQualification.PlatformDisposition.QUALIFIED
                                : WorkerSandboxQualification.PlatformDisposition
                                        .BLOCKED_NO_AGGREGATE_RESOURCE_JOB_BOUNDARY,
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
                        "LINUX_CGROUP_V2_AGGREGATE_MEMORY_PIDS_CPU_JOB_BOUNDARY",
                        "LINUX_CGROUP_V2_KILL_LEAVES_NO_SURVIVING_DESCENDANT",
                        "LINUX_PRLIMIT_PER_PROCESS_DEFENCE_IN_DEPTH_ONLY",
                        "LINUX_SANDBOX_SCRATCH_IS_PRIVATE_TMPFS_CHARGED_TO_THE_JOB_MEMORY_LIMIT",
                        "LINUX_RUNTIME_CAPABILITY_PROBE_REQUIRED",
                        "MINOS_SUPERVISED_PROVIDER_WRITE_QUOTA_BYTES_AND_ENTRIES",
                        "MINOS_WALL_CLOCK_TIMEOUT_AND_RUN_RESIDUE_RECLAMATION",
                        "LINUX_TOOLCHAIN_HOME_READ_ONLY_ROOT_AND_ALLOWLISTED_ETC_LINK_TARGETS",
                        "LINUX_PLAN_DECLARED_READ_ONLY_ROOTS_MOUNTED_EXACTLY_AFTER_WRITABLE_ROOTS"));
    }

    /** Aggregate containment this backend really enforces once the cgroup boundary exists. */
    static WorkerResourceContainment containment() {
        return new WorkerResourceContainment(
                "linux-cgroup2-bubblewrap",
                WorkerResourceContainment.Disposition.OS_ENFORCED,
                WorkerResourceContainment.Disposition.OS_ENFORCED,
                WorkerResourceContainment.Disposition.OS_ENFORCED,
                WorkerResourceContainment.Disposition.SUPERVISED_HARD_KILL,
                WorkerResourceContainment.Disposition.SUPERVISED_HARD_KILL,
                WorkerResourceContainment.Disposition.SUPERVISED_HARD_KILL,
                WorkerResourceContainment.Disposition.OS_ENFORCED,
                WorkerResourceContainment.Disposition.SUPERVISED_HARD_KILL,
                List.of(
                        "CGROUP_V2_MEMORY_MAX_AND_MEMORY_SWAP_MAX",
                        "CGROUP_V2_PIDS_MAX",
                        "CGROUP_V2_CPU_MAX",
                        "CGROUP_V2_CGROUP_KILL",
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
            throw new IllegalStateException("Linux bubblewrap backend is not qualified on this platform");
        }
        if (!(delegate instanceof ProcessSandboxCapableIndexerExecutor processExecutor)) {
            throw new IllegalArgumentException(
                    "qualified OS sandbox requires an executor with explicit process-sandbox capability");
        }
        String jobName = "minos-" + request.runId() + "-" + Long.toHexString(System.nanoTime());
        try (ContainedSandboxSession session = new ContainedSandboxSession(networkPolicy, jobName)) {
            return processExecutor.executeSandboxed(request, session);
        }
    }

    /** One provider execution: creates the cgroup boundary, then guarantees it is reclaimed. */
    final class ContainedSandboxSession
            implements ProcessIndexerExecutor.ProcessPlanTransformer, AutoCloseable {

        private final WorkerNetworkPolicy networkPolicy;
        private final String jobName;
        private volatile LinuxCgroupJob job;

        ContainedSandboxSession(WorkerNetworkPolicy networkPolicy, String jobName) {
            this.networkPolicy = networkPolicy;
            this.jobName = jobName;
        }

        @Override
        public IndexerProcessPlan transform(IndexerProcessPlan plan, Path runDirectory) throws Exception {
            Path root = LinuxCgroupJob.delegatedRoot().orElseThrow(() -> new IllegalStateException(
                    "Linux worker sandbox requires a delegated cgroup v2 job boundary; "
                            + "untrusted provider execution is fail-closed"));
            LinuxCgroupJob created = LinuxCgroupJob.create(root, jobName, LinuxCgroupJob.Limits.DEFAULT);
            job = created;
            return sandboxPlan(plan, runDirectory, networkPolicy, created);
        }

        @Override
        public Optional<ProviderWriteQuota> providerWriteQuota() {
            return Optional.of(ProviderWriteQuota.DEFAULT);
        }

        @Override
        public boolean mountsDeclaredSandboxMounts() {
            return true;
        }

        @Override
        public void killContainedJob() {
            LinuxCgroupJob current = job;
            if (current != null) current.kill();
        }

        @Override
        public void releaseContainment() {
            LinuxCgroupJob current = job;
            if (current == null) return;
            job = null;
            current.close();
        }

        @Override
        public void close() {
            releaseContainment();
        }
    }

    IndexerProcessPlan sandboxPlan(
            IndexerProcessPlan plan,
            Path runDirectory,
            WorkerNetworkPolicy networkPolicy
    ) throws IOException {
        return sandboxPlan(plan, runDirectory, networkPolicy, null);
    }

    IndexerProcessPlan sandboxPlan(
            IndexerProcessPlan plan,
            Path runDirectory,
            WorkerNetworkPolicy networkPolicy,
            LinuxCgroupJob job
    ) throws IOException {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(networkPolicy, "networkPolicy");
        Path working = plan.workingDirectory().toRealPath();
        Path artifact = plan.generatedArtifact().toAbsolutePath().normalize();
        Path artifactParent = Objects.requireNonNull(artifact.getParent(), "generated artifact parent");
        Files.createDirectories(artifactParent);
        artifactParent = artifactParent.toRealPath();
        Path run = runDirectory.toRealPath();

        List<String> sandbox = baseCommand(plan.timeout().plusSeconds(5).toSeconds(), networkPolicy);
        sandbox.add("--dev");
        sandbox.add("/dev");
        sandbox.add("--proc");
        sandbox.add(PROC);
        sandbox.add("--tmpfs");
        sandbox.add("/tmp");
        // The environment the provider really receives (ProcessIndexerExecutor applies the same allowlist):
        // a toolchain home it names, notably the project JDK, is a runtime root the provider must read.
        Map<String, String> providerEnvironment =
                ProviderProcessEnvironment.sanitize(new ProcessBuilder().environment(), plan.environment());
        addRuntimeReadOnlyBinds(sandbox, plan.command(), networkPolicy, minosHome, providerEnvironment);
        addWritableBind(sandbox, working);
        if (!artifactParent.startsWith(working)) addWritableBind(sandbox, artifactParent);
        if (!run.startsWith(working) && !run.startsWith(artifactParent)) addWritableBind(sandbox, run);
        // Declared read-only roots come after every writable bind, so that no writable mount can cover them,
        // and masked files come last, so that no later mount can expose them again.
        LinuxSandboxReadOnlyRoots.addDeclaredMounts(sandbox, plan, List.of(working, artifactParent, run), minosHome, run);
        sandbox.add("--chdir");
        sandbox.add(working.toString());
        sandbox.add("--");
        sandbox.addAll(plan.command());

        List<String> command = job == null ? List.copyOf(sandbox) : job.enterThenExec(shell, sandbox);
        return plan.withCommand(command);
    }

    private boolean probeOsIsolation() {
        List<String> command = baseCommand(5L, WorkerNetworkPolicy.DENY);
        try {
            addRuntimeReadOnlyBinds(command, List.of("/bin/true"), WorkerNetworkPolicy.DENY, minosHome, Map.of());
        } catch (IOException exception) {
            // Every other failure branch in this method logs before declining to qualify; this one
            // must too -- discover() feeds WorkerSandboxBackends.strongestAvailable(), which falls
            // back to a materially weaker sandbox tier for untrusted provider code when this
            // returns false, and that degradation must be observable, not silent.
            LOGGER.log(System.Logger.Level.WARNING,
                    "MINOS Linux sandbox capability probe could not resolve a required bind path: "
                            + exception.getClass().getSimpleName());
            return false;
        }
        command.add("--dev");
        command.add("/dev");
        command.add("--proc");
        command.add(PROC);
        command.add("--");
        command.add("/bin/true");
        return probeSucceeds(command);
    }

    /** True when the bounded probe command succeeds; every way it can fail is logged before declining to qualify. */
    private static boolean probeSucceeds(List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                LOGGER.log(System.Logger.Level.WARNING, "MINOS Linux sandbox capability probe timed out");
                return false;
            }
            String output = new String(process.getInputStream().readNBytes(8192), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                LOGGER.log(System.Logger.Level.WARNING, "MINOS Linux sandbox capability probe failed (exit="
                        + process.exitValue() + "): " + output);
                return false;
            }
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "MINOS Linux sandbox capability probe could not start", exception);
            return false;
        }
    }

    private List<String> baseCommand(long cpuSeconds, WorkerNetworkPolicy networkPolicy) {
        List<String> sandbox = new ArrayList<>();
        sandbox.add(prlimit.toString());
        sandbox.add("--as=" + MAX_ADDRESS_SPACE_BYTES + ":" + MAX_ADDRESS_SPACE_BYTES);
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
            Path minosHome,
            Map<String, String> providerEnvironment
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
        for (String key : TOOLCHAIN_HOME_KEYS) {
            String value = providerEnvironment.get(key);
            if (value != null) {
                addToolchainHomeBinds(command, mounted, key, value, minosHome);
            }
        }

        Path tools = minosHome == null ? null : minosHome.resolve(TOOLS_DIRECTORY).toAbsolutePath().normalize();
        for (String argument : providerCommand) {
            try {
                addProviderArgumentBind(command, mounted, argument, tools);
            } catch (InvalidPathException _) {
                // Non-path provider arguments stay opaque.
            }
        }
    }

    /**
     * Mounts what one absolute provider argument needs: the exact managed runtime root under
     * {@code tools}, or the exact regular file it names.
     */
    private static void addProviderArgumentBind(List<String> command, Set<Path> mounted, String argument, Path tools)
            throws IOException {
        Path candidate = Path.of(argument);
        if (!candidate.isAbsolute() || !Files.exists(candidate)) return;
        Path normalized = candidate.toAbsolutePath().normalize();
        Path real = candidate.toRealPath();
        if (mounted.stream().anyMatch(real::startsWith)) return;

        Path managedRoot = managedRuntimeRoot(normalized, tools);
        if (managedRoot != null) {
            addReadOnlyIfPresent(command, mounted, managedRoot);
        } else if (Files.isRegularFile(real)) {
            addReadOnlyFile(command, mounted, real, normalized);
        }
        // Arbitrary directories are deliberately not promoted to read roots. Project,
        // artifact and run directories are mounted explicitly by sandboxPlan.
    }

    /**
     * Mounts the toolchain root named by {@code key} read-only, then the exact allow-listed /etc targets
     * of its outward configuration links.
     *
     * <p>A distribution JDK lives under /usr (already mounted) but links each configuration file to
     * /etc/java-N-openjdk or a CA bundle under /etc/ssl/certs; a self-contained JDK lives anywhere and
     * needs its whole root, since its launcher loads {@code lib/libjli.so} relative to itself. The root
     * is the real directory, never its parent. A link target is mounted only as the exact file or
     * directory it designates and only when {@link #isAllowListedLinkTarget} accepts it: the root is named by an
     * environment variable and may be writable by the user, so a planted link must never expose a
     * profile file or an /etc secret.</p>
     *
     * @throws IllegalStateException when the root is too broad to mount (fail-closed, no path in the message)
     */
    static void addToolchainHomeBinds(
            List<String> command,
            Set<Path> mounted,
            String key,
            String value,
            Path minosHome
    ) throws IOException {
        Optional<Path> named = existingAbsoluteDirectory(value);
        if (named.isEmpty()) return;
        Path normalized = named.orElseThrow().toAbsolutePath().normalize();
        Path real = named.orElseThrow().toRealPath();
        requireMountableToolchainRoot(key, real, normalized, minosHome);
        addReadOnlyAt(command, mounted, real, real);
        if (!normalized.equals(real)) {
            if (Files.isSymbolicLink(normalized)) {
                addReadOnlyIfPresent(command, mounted, normalized);
            } else {
                addReadOnlyAt(command, mounted, real, normalized);
            }
        }
        int refused = addAllowListedLinkTargets(command, mounted, real);
        if (refused > 0) {
            LOGGER.log(System.Logger.Level.WARNING, "MINOS Linux sandbox did not mount " + refused
                    + " link target(s) of " + key + " outside the allow-listed Java configuration of /etc");
        }
    }

    /** The directory a toolchain variable names, when its value is an absolute path to an existing directory. */
    private static Optional<Path> existingAbsoluteDirectory(String value) {
        if (value.isBlank()) return Optional.empty();
        try {
            Path named = Path.of(value);
            return named.isAbsolute() && Files.isDirectory(named) ? Optional.of(named) : Optional.empty();
        } catch (InvalidPathException _) {
            // A value that is not a path names no toolchain root: nothing to mount, nothing granted.
            return Optional.empty();
        }
    }

    /**
     * Refuses, before anything is launched, a toolchain root that is too broad or is not a JDK.
     *
     * @throws IllegalStateException naming {@code key} only, never a path
     */
    private static void requireMountableToolchainRoot(String key, Path real, Path normalized, Path minosHome) {
        if (isOverBroadToolchainRoot(real, minosHome) || isOverBroadToolchainRoot(normalized, minosHome)) {
            throw new IllegalStateException(key + " names a directory too broad to mount in the Linux sandbox "
                    + "(a filesystem root, a top-level or system directory, the home directory or MINOS_HOME); "
                    + "point " + key + " to the JDK's own directory");
        }
        if (!isJdkShaped(real) || isPrivateMinosData(real, minosHome)) {
            throw new IllegalStateException(key + " does not name a JDK directory the Linux sandbox may mount "
                    + "(bin/java with a release file or lib/libjli.so, outside MINOS_HOME except its tools); "
                    + "point " + key + " to the JDK's own directory");
        }
    }

    /**
     * True for a real directory laid out as a JDK: its own {@code bin/java} regular file (no link out of
     * the root) and a {@code release} file or {@code lib/libjli.so}. A variable pointing at a profile
     * directory such as {@code ~/.ssh} is therefore never mounted.
     */
    static boolean isJdkShaped(Path realRoot) {
        Path java = realRoot.resolve("bin").resolve("java");
        if (!Files.isRegularFile(java, LinkOption.NOFOLLOW_LINKS)) return false;
        return Files.isRegularFile(realRoot.resolve("release"), LinkOption.NOFOLLOW_LINKS)
                || Files.isRegularFile(realRoot.resolve("lib").resolve("libjli.so"), LinkOption.NOFOLLOW_LINKS);
    }

    /** MINOS_HOME holds private control-plane data; only its managed tools may be a toolchain root. */
    static boolean isPrivateMinosData(Path realRoot, Path minosHome) {
        if (minosHome == null) return false;
        Path home = minosHome.toAbsolutePath().normalize();
        Path tools = home.resolve(TOOLS_DIRECTORY);
        if (Files.exists(home)) {
            try {
                home = home.toRealPath();
                tools = home.resolve(TOOLS_DIRECTORY);
            } catch (IOException _) {
                // The normalized spelling is compared instead; it is never broader.
            }
        }
        return realRoot.startsWith(home) && !realRoot.startsWith(tools);
    }

    /** A root whose mount would expose far more than one toolchain. */
    static boolean isOverBroadToolchainRoot(Path directory, Path minosHome) {
        if (directory.getNameCount() < 2) return true;
        for (String system : List.of("/etc", PROC, "/sys", "/dev", "/run")) {
            if (directory.startsWith(system)) return true;
        }
        List<Path> broad = new ArrayList<>();
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) broad.add(Path.of(home).toAbsolutePath().normalize());
        if (minosHome != null) broad.add(minosHome.toAbsolutePath().normalize());
        return broad.stream().anyMatch(location -> location.startsWith(directory));
    }

    /**
     * Walks the toolchain root without following links and mounts each outward link target that
     * {@link #isAllowListedLinkTarget} accepts, exactly; returns how many outward targets were refused.
     */
    private static int addAllowListedLinkTargets(List<String> command, Set<Path> mounted, Path root)
            throws IOException {
        int refused = 0;
        for (Path link : toolchainLinks(root)) {
            if (!mountLinkTarget(command, mounted, root, link)) refused++;
        }
        return refused;
    }

    /**
     * Mounts the exact target of one toolchain link when it is outward and allow-listed.
     *
     * @return false only for an outward target that is refused; a dangling, inward or already
     *         covered target needs no mount
     */
    private static boolean mountLinkTarget(List<String> command, Set<Path> mounted, Path root, Path link)
            throws IOException {
        Path target;
        try {
            target = link.toRealPath();
        } catch (IOException _) {
            // A dangling link resolves to nothing inside the sandbox either: there is nothing to mount.
            return true;
        }
        if (target.startsWith(root) || mounted.stream().anyMatch(target::startsWith)) return true;
        if (!isAllowListedLinkTarget(target)) return false;
        addReadOnlyAt(command, mounted, target, target);
        // The link resolves inside the sandbox by its literal spelling; when an intermediate link
        // makes that spelling differ from the real target, the same source is bound there too.
        Path literal = link.getParent().resolve(Files.readSymbolicLink(link)).toAbsolutePath().normalize();
        if (!literal.equals(target) && isAllowListedLinkTarget(literal)) {
            addReadOnlyAt(command, mounted, target, literal);
        }
        return true;
    }

    /** The symbolic links of a toolchain root, found without following any link and within fixed bounds. */
    private static List<Path> toolchainLinks(Path root) throws IOException {
        List<Path> links = new ArrayList<>();
        Files.walkFileTree(root, Set.of(), MAX_TOOLCHAIN_SCAN_DEPTH, new SimpleFileVisitor<>() {
            private int visited;

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isSymbolicLink()) links.add(file);
                return ++visited >= MAX_TOOLCHAIN_SCAN_ENTRIES ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException unreadable) {
                // An unreadable entry is not mounted either: skipping it grants nothing.
                return FileVisitResult.CONTINUE;
            }
        });
        return links;
    }

    /**
     * True for public Java configuration under /etc only: the exact file or directory below a
     * first-level {@code /etc/java*} directory or below one of {@link #ETC_LINK_TARGET_ROOTS}, never the
     * allowed directory itself and never anything else under /etc.
     */
    static boolean isAllowListedLinkTarget(Path target) {
        Path normalized = target.toAbsolutePath().normalize();
        if (normalized.getNameCount() >= 3 && normalized.startsWith(ETC)
                && isJavaConfigurationDirectoryName(normalized.getName(1).toString())) return true;
        return ETC_LINK_TARGET_ROOTS.stream()
                .anyMatch(allowed -> normalized.startsWith(allowed) && !normalized.equals(allowed));
    }

    /**
     * First-level /etc directory names a JDK package uses for its configuration: {@code java}
     * (Fedora/RHEL), {@code java-21-openjdk} (Debian/Ubuntu), {@code java21-openjdk} (Arch). A name that
     * merely starts with "java", such as {@code javascript}, is not one.
     */
    static boolean isJavaConfigurationDirectoryName(String name) {
        return "java".equals(name) || name.startsWith("java-") || JAVA_VERSIONED_DIRECTORY.matcher(name).matches();
    }

    private static final Pattern JAVA_VERSIONED_DIRECTORY = Pattern.compile("java\\d+(-[A-Za-z0-9._-]+)?");

    /** Binds {@code source} read-only at {@code destination} once, unless a mounted root already covers it. */
    private static void addReadOnlyAt(List<String> command, Set<Path> mounted, Path source, Path destination) {
        Path marker = destination.toAbsolutePath().normalize();
        if (mounted.stream().anyMatch(marker::startsWith)) return;
        if (!mounted.add(marker)) return;
        addDestinationParents(command, marker);
        command.add(RO_BIND);
        command.add(source.toString());
        command.add(marker.toString());
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
        command.add(RO_BIND);
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
        command.add(RO_BIND);
        command.add(realSource.toString());
        command.add(marker.toString());
    }

    private static void addWritableBind(List<String> command, Path directory) {
        addDestinationParents(command, directory);
        command.add("--bind");
        command.add(directory.toString());
        command.add(directory.toString());
    }

    static void addDestinationParents(List<String> command, Path destination) {
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
            throw new IllegalArgumentException(label + " must be an executable regular file: " + leafName(path));
        }
        return path;
    }
}
