package com.minos.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Aggregate Linux job boundary for one provider execution, backed by cgroup v2.
 *
 * <p>The cgroup membership itself is the process-ownership authority: a provider joins the cgroup
 * before any provider code executes and every descendant inherits that membership across
 * {@code fork}, {@code setsid} and reparenting. Resource limits are an optional additional policy
 * used by sandboxed workers; ownership-only callers deliberately leave controller limits unchanged.</p>
 *
 * <p>When resource limits are requested, {@code memory.max}, {@code pids.max} and {@code cpu.max}
 * bound the aggregate process tree. Strong ownership requires the kernel {@code cgroup.kill}
 * primitive so MINOS never falls back to signalling raw PIDs that may have been reused.</p>
 */
final class LinuxCgroupJob implements AutoCloseable {

    private static final System.Logger LOGGER = System.getLogger(LinuxCgroupJob.class.getName());

    static final String ROOT_ENVIRONMENT_VARIABLE = "MINOS_SANDBOX_CGROUP_ROOT";
    static final Path CGROUP_MOUNT = Path.of("/sys/fs/cgroup");
    static final String CONTROLLER_DIRECTORY = "minos-controller";
    static final String PROCS_FILE = "cgroup.procs";
    static final long CPU_PERIOD_MICROS = 100_000L;

    private static final String KILL_FILE = "cgroup.kill";
    private static final java.util.regex.Pattern SAFE_JOB_NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,95}");
    private static final List<String> REQUIRED_CONTROLLERS = List.of("memory", "pids", "cpu");
    private static final String SUBTREE_CONTROL_REQUEST = "+memory +pids +cpu";
    private static final int MAX_KILL_POLLS = 100;
    private static final long KILL_POLL_MILLIS = 50L;
    private static final long MAX_STALE_JOB_SWEEP = 4_096L;

    private static final Object DISCOVERY_LOCK = new Object();
    private static boolean delegationProbed;
    private static Optional<Path> delegation = Optional.empty();

    private final Path directory;
    private boolean closed;

    LinuxCgroupJob(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    }

    /**
     * Returns the delegated cgroup root MINOS may create job boundaries in, probing it for real.
     *
     * <p>Discovery is memoized because qualifying a root moves the MINOS process itself into a
     * dedicated child cgroup so that the root can carry {@code cgroup.subtree_control}.</p>
     */
    static Optional<Path> delegatedRoot() {
        synchronized (DISCOVERY_LOCK) {
            if (!delegationProbed) {
                delegation = discoverDelegatedRoot();
                delegationProbed = true;
            }
            return delegation;
        }
    }

    /** Test seam: forgets the memoized delegation so a probe can be re-evaluated. */
    static void resetDelegationForTesting() {
        synchronized (DISCOVERY_LOCK) {
            delegationProbed = false;
            delegation = Optional.empty();
        }
    }

    private static Optional<Path> discoverDelegatedRoot() {
        if (WorkerSandboxQualification.currentPlatform() != WorkerSandboxQualification.Platform.LINUX) {
            return Optional.empty();
        }
        for (Path candidate : candidateRoots()) {
            if (qualifyRoot(candidate)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private static Set<Path> candidateRoots() {
        Set<Path> candidates = new LinkedHashSet<>();
        String configured = System.getenv(ROOT_ENVIRONMENT_VARIABLE);
        if (configured != null && !configured.isBlank()) {
            Path root = Path.of(configured).toAbsolutePath().normalize();
            if (root.startsWith(CGROUP_MOUNT) && !root.equals(CGROUP_MOUNT)) candidates.add(root);
        }
        ownCgroup().ifPresent(candidates::add);
        return candidates;
    }

    /** Resolves the cgroup v2 directory the current MINOS process belongs to. */
    static Optional<Path> ownCgroup() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/cgroup"), StandardCharsets.UTF_8)) {
                if (!line.startsWith("0::")) continue;
                String relative = line.substring(3).trim();
                if (relative.isEmpty() || relative.equals("/")) return Optional.of(CGROUP_MOUNT);
                Path resolved = CGROUP_MOUNT.resolve(relative.substring(1)).normalize();
                return resolved.startsWith(CGROUP_MOUNT) ? Optional.of(resolved) : Optional.empty();
            }
        } catch (IOException | RuntimeException ignored) {
            // A host without cgroup v2 simply has no delegated root.
        }
        return Optional.empty();
    }

    private static boolean qualifyRoot(Path root) {
        try {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return false;
            if (!availableControllers(root).containsAll(REQUIRED_CONTROLLERS)) return false;
            relocateSelf(root);
            enableSubtreeControl(root);
            if (!probe(root)) return false;
            reclaimStaleJobs(root);
            return true;
        } catch (IOException | RuntimeException exception) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "MINOS Linux cgroup delegation probe rejected " + root, exception);
            return false;
        }
    }

    private static Set<String> availableControllers(Path root) throws IOException {
        String value = Files.readString(root.resolve("cgroup.controllers"), StandardCharsets.UTF_8);
        return Set.of(value.trim().toLowerCase(Locale.ROOT).split("\\s+"));
    }

    /**
     * Moves the MINOS process into a dedicated child so the delegated root becomes process-free.
     * cgroup v2 refuses {@code cgroup.subtree_control} on a non-root cgroup that still holds
     * processes, and the sandbox process must be migrated by a writer that owns the common ancestor.
     */
    private static void relocateSelf(Path root) throws IOException {
        Path controller = root.resolve(CONTROLLER_DIRECTORY);
        if (!Files.isDirectory(controller, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(controller);
        Optional<Path> own = ownCgroup();
        if (own.isPresent() && own.orElseThrow().equals(controller)) return;
        Files.writeString(
                controller.resolve(PROCS_FILE),
                Long.toString(ProcessHandle.current().pid()),
                StandardCharsets.UTF_8);
    }

    private static void enableSubtreeControl(Path root) throws IOException {
        Path control = root.resolve("cgroup.subtree_control");
        Set<String> enabled = Set.of(
                Files.readString(control, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT).split("\\s+"));
        if (enabled.containsAll(REQUIRED_CONTROLLERS)) return;
        Files.writeString(control, SUBTREE_CONTROL_REQUEST, StandardCharsets.UTF_8);
        Set<String> reread = Set.of(
                Files.readString(control, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT).split("\\s+"));
        if (!reread.containsAll(REQUIRED_CONTROLLERS)) {
            throw new IOException("delegated cgroup root does not expose memory/pids/cpu to its children: " + root);
        }
    }

    /**
     * Kills and removes cgroups left behind by a MINOS process that was itself killed.
     * The delegated root must never accumulate residue a provider could rely on.
     */
    private static void reclaimStaleJobs(Path root) {
        try (java.util.stream.Stream<Path> children = Files.list(root)) {
            children.limit(MAX_STALE_JOB_SWEEP)
                    .filter(child -> Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS))
                    .filter(child -> String.valueOf(child.getFileName()).startsWith("minos-"))
                    .filter(child -> !CONTROLLER_DIRECTORY.equals(String.valueOf(child.getFileName())))
                    .forEach(child -> {
                        try {
                            new LinuxCgroupJob(child).close();
                        } catch (RuntimeException exception) {
                            LOGGER.log(System.Logger.Level.WARNING,
                                    "MINOS could not reclaim stale cgroup " + child, exception);
                        }
                    });
        } catch (IOException | RuntimeException exception) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "MINOS could not enumerate stale cgroups in " + root, exception);
        }
    }

    private static boolean probe(Path root) {
        Path probe = root.resolve("minos-probe-" + UUID.randomUUID());
        try {
            LinuxCgroupJob job = configure(probe, Limits.DEFAULT);
            job.close();
            return true;
        } catch (IOException | RuntimeException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "MINOS Linux cgroup capability probe failed", exception);
            deleteProbeQuietly(probe);
            return false;
        }
    }

    private static void deleteProbeQuietly(Path probe) {
        try {
            Files.deleteIfExists(probe);
        } catch (IOException ignored) {
            // A probe leftover is diagnosed by the next probe, never silently trusted.
        }
    }

    /** Creates and configures a resource-limited job boundary for one sandboxed provider execution. */
    static LinuxCgroupJob create(Path root, String name, Limits limits) throws IOException {
        Objects.requireNonNull(limits, "limits");
        return configure(jobDirectory(root, name), limits);
    }

    /**
     * Creates a cgroup used strictly as a process-ownership boundary.
     *
     * <p>No memory, pids, swap or CPU limit is changed. This is intentionally distinct from the
     * sandbox resource policy: managed providers get strong descendant ownership without receiving
     * an unrelated resource-limit behavior change.</p>
     */
    static LinuxCgroupJob createOwnershipOnly(Path root, String name) throws IOException {
        Path directory = jobDirectory(root, name);
        Files.createDirectory(directory);
        LinuxCgroupJob job = new LinuxCgroupJob(directory);
        try {
            if (!Files.exists(directory.resolve(PROCS_FILE), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("cgroup ownership file is missing: " + directory.resolve(PROCS_FILE));
            }
            job.requireKillSwitch();
            return job;
        } catch (IOException | RuntimeException failure) {
            discardUnstartedCgroup(directory, failure);
            throw failure;
        }
    }

    private static Path jobDirectory(Path root, String name) throws IOException {
        Path normalizedRoot = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        String safe = Objects.requireNonNull(name, "name");
        if (!SAFE_JOB_NAME.matcher(safe).matches()) {
            throw new IOException("cgroup job name is not a safe single path segment: " + name);
        }
        Path directory = normalizedRoot.resolve(safe).toAbsolutePath().normalize();
        if (!directory.startsWith(normalizedRoot)
                || directory.equals(normalizedRoot)
                || !directory.startsWith(CGROUP_MOUNT)) {
            throw new IOException("cgroup job directory escapes the delegated cgroup root: " + directory);
        }
        return directory;
    }

    private static LinuxCgroupJob configure(Path directory, Limits limits) throws IOException {
        Files.createDirectory(directory);
        LinuxCgroupJob job = new LinuxCgroupJob(directory);
        try {
            job.write("memory.max", Long.toString(limits.memoryBytes()));
            job.writeIfPresent("memory.swap.max", "0");
            job.write("pids.max", Long.toString(limits.processes()));
            job.write("cpu.max", limits.cpuMicrosPerPeriod() + " " + CPU_PERIOD_MICROS);
            job.requireApplied("memory.max", Long.toString(limits.memoryBytes()));
            job.requireApplied("pids.max", Long.toString(limits.processes()));
            job.requireApplied("cpu.max", limits.cpuMicrosPerPeriod() + " " + CPU_PERIOD_MICROS);
            job.requireKillSwitch();
            return job;
        } catch (IOException | RuntimeException exception) {
            discardUnstartedCgroup(directory, exception);
            throw exception;
        }
    }

    private static void discardUnstartedCgroup(Path directory, Throwable failure) {
        try {
            Files.deleteIfExists(directory);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    Path directory() {
        return directory;
    }

    /**
     * Wraps a command so that the launching process joins this cgroup before it execs anything
     * else. Every descendant inherits the cgroup, so no {@code fork}, {@code setsid} or reparenting
     * can leave the ownership boundary.
     */
    List<String> enterThenExec(Path shell, List<String> command) {
        Objects.requireNonNull(shell, "shell");
        if (Objects.requireNonNull(command, "command").isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        List<String> wrapped = new ArrayList<>(command.size() + 4);
        wrapped.add(shell.toString());
        wrapped.add("-c");
        wrapped.add("printf '%s' \"$$\" > \"$0/cgroup.procs\" || exit 97; exec \"$@\"");
        wrapped.add(directory.toString());
        wrapped.addAll(command);
        return List.copyOf(wrapped);
    }

    /** Number of processes still alive inside the boundary. Read failures are containment failures. */
    synchronized long aliveProcesses() {
        if (closed) return 0L;
        try {
            return members().size();
        } catch (IOException exception) {
            throw containmentFailure("unable to read cgroup membership", exception);
        }
    }

    /** Atomically terminates every process in the boundary using the kernel ownership primitive. */
    void kill() {
        kill(MAX_KILL_POLLS, KILL_POLL_MILLIS);
    }

    /** Package-private bounded variant used by deterministic fault-injection tests. */
    synchronized void kill(int maximumPolls, long pollMillis) {
        if (closed) return;
        if (maximumPolls < 1) throw new IllegalArgumentException("maximumPolls must be positive");
        if (pollMillis < 0L) throw new IllegalArgumentException("pollMillis must not be negative");
        killInternal(maximumPolls, pollMillis);
    }

    private void killInternal(int maximumPolls, long pollMillis) {
        Path killSwitch;
        try {
            killSwitch = requireKillSwitch();
            Files.writeString(killSwitch, "1", StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw containmentFailure("kernel cgroup.kill is unavailable or could not be triggered", exception);
        }

        for (int poll = 0; poll < maximumPolls; poll++) {
            List<Long> current;
            try {
                current = members();
            } catch (IOException exception) {
                throw containmentFailure("unable to verify cgroup membership after cgroup.kill", exception);
            }
            if (current.isEmpty()) return;
            if (poll + 1 >= maximumPolls) {
                throw containmentFailure("cgroup still contains " + current.size()
                        + " process(es) after kernel cgroup.kill", null);
            }
            if (pollMillis == 0L) {
                Thread.onSpinWait();
                continue;
            }
            try {
                Thread.sleep(pollMillis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw containmentFailure("interrupted while verifying cgroup termination", exception);
            }
        }
    }

    private List<Long> members() throws IOException {
        List<Long> result = new ArrayList<>();
        for (String line : Files.readAllLines(directory.resolve(PROCS_FILE), StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String value = line.trim();
            try {
                long pid = Long.parseLong(value);
                if (pid <= 0L) throw new NumberFormatException("pid must be positive");
                result.add(pid);
            } catch (NumberFormatException exception) {
                throw new IOException("invalid PID in cgroup.procs: " + value, exception);
            }
        }
        return List.copyOf(result);
    }

    private Path requireKillSwitch() throws IOException {
        Path killSwitch = directory.resolve(KILL_FILE);
        if (Files.isSymbolicLink(killSwitch)
                || !Files.isRegularFile(killSwitch, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("kernel cgroup.kill control is missing: " + killSwitch);
        }
        return killSwitch;
    }

    private IllegalStateException containmentFailure(String detail, Throwable cause) {
        String message = "strong cgroup containment cleanup failed for " + directory + ": " + detail;
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        killInternal(MAX_KILL_POLLS, KILL_POLL_MILLIS);
        try {
            Files.deleteIfExists(directory);
        } catch (IOException exception) {
            throw containmentFailure("unable to reclaim empty cgroup", exception);
        }
        closed = true;
    }

    private void write(String file, String value) throws IOException {
        Files.writeString(directory.resolve(file), value, StandardCharsets.UTF_8);
    }

    private void writeIfPresent(String file, String value) {
        try {
            if (Files.exists(directory.resolve(file), LinkOption.NOFOLLOW_LINKS)) write(file, value);
        } catch (IOException ignored) {
            // Swap accounting is optional; memory.max still bounds anonymous memory in limited mode.
        }
    }

    private void requireApplied(String file, String expected) throws IOException {
        String actual;
        try {
            actual = Files.readString(directory.resolve(file), StandardCharsets.UTF_8).trim();
        } catch (NoSuchFileException exception) {
            throw new IOException("cgroup controller file is missing: " + file, exception);
        }
        if (!actual.equals(expected.trim())) {
            throw new IOException("cgroup limit " + file + " was not applied: expected=" + expected
                    + " actual=" + actual);
        }
    }

    /** Aggregate limits applied to the whole provider process tree in sandbox resource mode. */
    record Limits(long memoryBytes, long processes, long cpuMicrosPerPeriod) {

        static final Limits DEFAULT = new Limits(
                LinuxBubblewrapWorkerSandboxBackend.MAX_JOB_MEMORY_BYTES,
                LinuxBubblewrapWorkerSandboxBackend.MAX_PROCESSES,
                LinuxBubblewrapWorkerSandboxBackend.MAX_CPU_MICROS_PER_PERIOD);

        Limits {
            if (memoryBytes < 1L || processes < 1L || cpuMicrosPerPeriod < 1L) {
                throw new IllegalArgumentException("cgroup job limits must be positive");
            }
        }
    }
}
