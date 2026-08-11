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
 * Aggregate Linux job boundary for one untrusted provider execution, backed by cgroup v2.
 *
 * <p>{@code prlimit} bounds a single process and is multiplied by every {@code fork}: it is a
 * defence in depth, never an aggregate guarantee. This class owns the real boundary:</p>
 *
 * <ul>
 *   <li>{@code memory.max} and {@code memory.swap.max} bound the memory of the entire process tree,
 *       including the private {@code tmpfs} scratch the sandbox mounts on {@code /tmp};</li>
 *   <li>{@code pids.max} bounds the aggregate number of processes and threads;</li>
 *   <li>{@code cpu.max} bounds aggregate CPU bandwidth;</li>
 *   <li>{@code cgroup.kill} terminates every member atomically, so no descendant survives.</li>
 * </ul>
 *
 * <p>The sandbox process joins the cgroup before it executes any provider code and the cgroup
 * filesystem is never exposed inside the sandbox mount namespace, so the provider can neither
 * escape the group nor relax its limits.</p>
 */
final class LinuxCgroupJob implements AutoCloseable {

    static final String ROOT_ENVIRONMENT_VARIABLE = "MINOS_SANDBOX_CGROUP_ROOT";
    static final Path CGROUP_MOUNT = Path.of("/sys/fs/cgroup");
    static final String CONTROLLER_DIRECTORY = "minos-controller";
    static final String PROCS_FILE = "cgroup.procs";
    static final long CPU_PERIOD_MICROS = 100_000L;

    private static final java.util.regex.Pattern SAFE_JOB_NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,95}");
    private static final List<String> REQUIRED_CONTROLLERS = List.of("memory", "pids", "cpu");
    private static final String SUBTREE_CONTROL_REQUEST = "+memory +pids +cpu";
    private static final int MAX_KILL_POLLS = 100;
    private static final long KILL_POLL_MILLIS = 50L;
    private static final long MAX_STALE_JOB_SWEEP = 4_096L;

    private static final Object DISCOVERY_LOCK = new Object();
    private static Optional<Path> discovered;

    private final Path directory;

    private LinuxCgroupJob(Path directory) {
        this.directory = directory;
    }

    /**
     * Returns the delegated cgroup root MINOS may create job boundaries in, probing it for real.
     *
     * <p>Discovery is memoized because qualifying a root moves the MINOS process itself into a
     * dedicated child cgroup so that the root can carry {@code cgroup.subtree_control}.</p>
     */
    static Optional<Path> delegatedRoot() {
        synchronized (DISCOVERY_LOCK) {
            if (discovered == null) discovered = discoverDelegatedRoot();
            return discovered;
        }
    }

    /** Test seam: forgets the memoized delegation so a probe can be re-evaluated. */
    static void resetDelegationForTesting() {
        synchronized (DISCOVERY_LOCK) {
            discovered = null;
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
            System.err.println("MINOS Linux cgroup delegation probe rejected " + root + ": "
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage());
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
     * Kills and removes sandbox cgroups left behind by a MINOS process that was itself killed.
     * The delegated root must never accumulate residue a hostile provider could rely on.
     */
    private static void reclaimStaleJobs(Path root) {
        try (java.util.stream.Stream<Path> children = Files.list(root)) {
            children.limit(MAX_STALE_JOB_SWEEP)
                    .filter(child -> Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS))
                    .filter(child -> String.valueOf(child.getFileName()).startsWith("minos-"))
                    .filter(child -> !CONTROLLER_DIRECTORY.equals(String.valueOf(child.getFileName())))
                    .forEach(child -> new LinuxCgroupJob(child).close());
        } catch (IOException | RuntimeException exception) {
            System.err.println("MINOS could not reclaim stale sandbox cgroups in " + root + ": "
                    + exception.getMessage());
        }
    }

    private static boolean probe(Path root) {
        Path probe = root.resolve("minos-probe-" + UUID.randomUUID());
        try {
            LinuxCgroupJob job = configure(probe, Limits.DEFAULT);
            job.close();
            return true;
        } catch (IOException | RuntimeException exception) {
            System.err.println("MINOS Linux cgroup capability probe failed: "
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            try {
                Files.deleteIfExists(probe);
            } catch (IOException ignored) {
                // A probe leftover is diagnosed by the next probe, never silently trusted.
            }
            return false;
        }
    }

    /** Creates and configures a job boundary for one provider execution. */
    static LinuxCgroupJob create(Path root, String name, Limits limits) throws IOException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(limits, "limits");
        String safe = Objects.requireNonNull(name, "name");
        if (!SAFE_JOB_NAME.matcher(safe).matches()) {
            throw new IOException("cgroup job name is not a safe single path segment: " + name);
        }
        Path directory = root.resolve(safe).toAbsolutePath().normalize();
        if (!directory.startsWith(root) || directory.equals(root) || !directory.startsWith(CGROUP_MOUNT)) {
            throw new IOException("cgroup job directory escapes the delegated cgroup root: " + directory);
        }
        return configure(directory, limits);
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
            return job;
        } catch (IOException | RuntimeException exception) {
            job.close();
            throw exception;
        }
    }

    Path directory() {
        return directory;
    }

    /**
     * Wraps a command so that the launching process joins this cgroup before it execs anything
     * else. Every descendant inherits the cgroup, so no {@code fork} can leave the boundary.
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

    /** Number of processes still alive inside the boundary. */
    long aliveProcesses() {
        try {
            return Files.readAllLines(directory.resolve(PROCS_FILE), StandardCharsets.UTF_8).stream()
                    .filter(line -> !line.isBlank())
                    .count();
        } catch (IOException exception) {
            return 0L;
        }
    }

    /** Atomically terminates every process in the boundary. */
    void kill() {
        Path killSwitch = directory.resolve("cgroup.kill");
        try {
            if (Files.exists(killSwitch, LinkOption.NOFOLLOW_LINKS)) {
                Files.writeString(killSwitch, "1", StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // Fall through to the explicit signal path below.
        }
        for (int poll = 0; poll < MAX_KILL_POLLS; poll++) {
            List<Long> members = members();
            if (members.isEmpty()) return;
            members.forEach(pid -> ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly));
            try {
                Thread.sleep(KILL_POLL_MILLIS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private List<Long> members() {
        try {
            List<Long> members = new ArrayList<>();
            for (String line : Files.readAllLines(directory.resolve(PROCS_FILE), StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    members.add(Long.parseLong(line.trim()));
                } catch (NumberFormatException ignored) {
                    // cgroup.procs only ever holds decimal pids; anything else is not addressable.
                }
            }
            return members;
        } catch (IOException exception) {
            return List.of();
        }
    }

    @Override
    public void close() {
        kill();
        try {
            Files.deleteIfExists(directory);
        } catch (IOException exception) {
            System.err.println("MINOS could not reclaim sandbox cgroup " + directory + ": " + exception.getMessage());
        }
    }

    private void write(String file, String value) throws IOException {
        Files.writeString(directory.resolve(file), value, StandardCharsets.UTF_8);
    }

    private void writeIfPresent(String file, String value) {
        try {
            if (Files.exists(directory.resolve(file), LinkOption.NOFOLLOW_LINKS)) write(file, value);
        } catch (IOException ignored) {
            // Swap accounting is optional; memory.max still bounds anonymous memory.
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

    /** Aggregate limits applied to the whole provider process tree. */
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
