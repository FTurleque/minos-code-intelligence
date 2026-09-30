package com.minos.runtime.local;

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
 *
 * <p>Diagnostics never carry an absolute path: journal entries and failure messages name a cgroup
 * relative to the delegated root (its directory name) and the delegated root relative to the cgroup
 * mount, including in the failures they attach.</p>
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
    /** Residues named in the single qualification WARNING; the remainder is counted, not listed. */
    static final int MAX_REPORTED_RESIDUES = 32;
    private static final int MAX_DESCRIBED_CAUSES = 4;
    private static final int MAX_REDACTION_DEPTH = 16;

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

    static boolean qualifyRoot(Path root) {
        try {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return false;
            if (!availableControllers(root).containsAll(REQUIRED_CONTROLLERS)) return false;
            relocateSelf(root);
            enableSubtreeControl(root);
            if (!probe(root)) return false;
            reclaimAndReportStaleJobs(root);
            return true;
        } catch (IOException | RuntimeException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "MINOS Linux cgroup delegation probe rejected cgroup root "
                    + displayRoot(root) + ": " + describeFailure(exception, root));
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
            throw new IOException("delegated cgroup root does not expose memory/pids/cpu to its children");
        }
    }

    /**
     * Kills and removes cgroups left behind by a MINOS process that was itself killed.
     * The delegated root must never accumulate residue a provider could rely on.
     *
     * <p>Several MINOS processes (CLI, MCP server, IDE plugin) share one delegated root, so a
     * discovered cgroup is only reclaimed when {@link CgroupJobOwnership} proves its owner is dead:
     * the owner PID carried in the cgroup name is gone or was reused by another process. Cgroups of
     * a live MINOS process, of this process, or unmarked cgroups that still hold processes are left
     * intact. An orphaned boundary that still contains processes but cannot be killed, or whose
     * membership cannot be read, is a containment failure and rejects the delegated root. Only
     * deletion of an already empty cgroup remains best-effort because it cannot hide surviving
     * provider processes.</p>
     *
     * <p>This sweep does not journal what it leaves intact; {@link #reclaimAndReportStaleJobs} does.</p>
     */
    static StaleSweep reclaimStaleJobs(Path root) throws IOException {
        return reclaimStaleJobs(root, SweepContext.system());
    }

    /** Package-private variant with injectable ownership evidence, removal and bound, for deterministic tests. */
    static StaleSweep reclaimStaleJobs(Path root, SweepContext context) throws IOException {
        List<String> reclaimed = new ArrayList<>();
        List<Residue> leftIntact = new ArrayList<>();
        try (java.util.stream.Stream<Path> children = Files.list(root)) {
            for (Path child : children.limit(context.maximumEntries()).toList()) {
                if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) continue;
                String name = String.valueOf(child.getFileName());
                if (!name.startsWith("minos-")) continue;
                if (CONTROLLER_DIRECTORY.equals(name)) continue;
                CgroupJobOwnership.Verdict verdict = reclaimStaleJob(child, name, context);
                if (verdict.reclaim()) reclaimed.add(name);
                else leftIntact.add(new Residue(name, verdict.reason()));
            }
        }
        return new StaleSweep(List.copyOf(reclaimed), List.copyOf(leftIntact), 0);
    }

    /**
     * Sweeps the delegated root during its qualification and reports, in a single WARNING, every
     * cgroup the sweep leaves intact.
     *
     * <p>The sweep never kills a cgroup whose owner may be alive (a live MINOS instance, an unmarked
     * cgroup that still holds processes). Such residues still consume the {@code pids} and
     * {@code memory} budget of the delegated root, so the operator is told about each of them once
     * per qualification: its name relative to the root and the reason it was left intact. The report
     * is bounded to {@link #MAX_REPORTED_RESIDUES} named residues; the others are counted.</p>
     */
    static StaleSweep reclaimAndReportStaleJobs(Path root) throws IOException {
        return reclaimAndReportStaleJobs(root, SweepContext.system());
    }

    /** Package-private variant with injectable ownership evidence, removal and bound, for deterministic tests. */
    static StaleSweep reclaimAndReportStaleJobs(Path root, SweepContext context) throws IOException {
        StaleSweep sweep = reclaimStaleJobs(root, context);
        if (!sweep.residues().isEmpty()) {
            LOGGER.log(System.Logger.Level.WARNING, residueReport(root, sweep.residues()));
        }
        return sweep;
    }

    static String residueReport(Path root, List<Residue> residues) {
        StringBuilder report = new StringBuilder("MINOS left ")
                .append(residues.size())
                .append(" cgroup(s) intact under the delegated cgroup root ")
                .append(displayRoot(root))
                .append("; they keep consuming its pids and memory budget until their owner exits or an operator"
                        + " removes them: ");
        int listed = Math.min(residues.size(), MAX_REPORTED_RESIDUES);
        for (int index = 0; index < listed; index++) {
            Residue residue = residues.get(index);
            if (index > 0) report.append("; ");
            report.append(residue.name()).append(" (").append(residue.reason()).append(')');
        }
        if (residues.size() > listed) {
            report.append("; and ").append(residues.size() - listed).append(" more");
        }
        return report.toString();
    }

    /** Applies the ownership decision to one discovered cgroup and reclaims it when the decision says so. */
    private static CgroupJobOwnership.Verdict reclaimStaleJob(Path child, String name, SweepContext context) {
        LinuxCgroupJob stale = new LinuxCgroupJob(child);
        Optional<CgroupJobOwnership.Mark> mark = CgroupJobOwnership.Mark.parse(name);
        CgroupJobOwnership.Verdict verdict = CgroupJobOwnership.decide(
                mark, context.self(), context.owners().apply(child), stale::aliveProcesses);
        if (!verdict.reclaim()) {
            // Reported once, together with every other residue, by reclaimAndReportStaleJobs.
            return verdict;
        }
        LOGGER.log(System.Logger.Level.DEBUG, "MINOS reclaims stale cgroup " + name + ": " + verdict.reason());
        if (stale.aliveProcesses() > 0L) {
            stale.kill(context.killPolls(), context.killPollMillis());
        }
        try {
            context.removal().remove(child);
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "MINOS could not remove already-empty stale cgroup " + name
                    + ": " + describeFailure(exception, child.getParent()));
        }
        return verdict;
    }

    /** Removes a cgroup directory; the kernel refuses while a process or a child cgroup remains in it. */
    @FunctionalInterface
    interface CgroupRemoval {

        CgroupRemoval KERNEL = Files::deleteIfExists;

        void remove(Path cgroup) throws IOException;
    }

    /**
     * Everything a sweep consults besides the directory it walks: the mark of the sweeping process, how
     * the process table is read for a given cgroup, how a cgroup is removed and how many entries are
     * examined. {@link #system()} is the production wiring; tests inject each part so that the decision
     * is exercised on any host.
     */
    record SweepContext(
            CgroupJobOwnership.Mark self,
            java.util.function.Function<Path, CgroupJobOwnership.OwnerLookup> owners,
            CgroupRemoval removal,
            long maximumEntries,
            int killPolls,
            long killPollMillis) {

        SweepContext {
            Objects.requireNonNull(self, "self");
            Objects.requireNonNull(owners, "owners");
            Objects.requireNonNull(removal, "removal");
            if (maximumEntries < 1L) throw new IllegalArgumentException("maximumEntries must be positive");
            if (killPolls < 1) throw new IllegalArgumentException("killPolls must be positive");
            if (killPollMillis < 0L) throw new IllegalArgumentException("killPollMillis must not be negative");
        }

        static SweepContext system() {
            return new SweepContext(CgroupJobOwnership.CURRENT, CgroupJobOwnership.OwnerLookup::system,
                    CgroupRemoval.KERNEL, MAX_STALE_JOB_SWEEP, MAX_KILL_POLLS, KILL_POLL_MILLIS);
        }
    }

    /** Outcome of one stale sweep, by cgroup name (never a path), for diagnostics and tests. */
    record StaleSweep(List<String> reclaimed, List<Residue> residues, int notExamined) {
        StaleSweep {
            reclaimed = List.copyOf(reclaimed);
            residues = List.copyOf(residues);
        }

        /** Names of the cgroups the sweep left intact. */
        List<String> leftIntact() {
            return residues.stream().map(Residue::name).toList();
        }
    }

    /** A cgroup the sweep left intact, by name (never a path), with the reason of the decision. */
    record Residue(String name, String reason) {
        Residue {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(reason, "reason");
        }
    }

    static boolean probe(Path root) {
        Path probe = root.resolve(CgroupJobOwnership.CURRENT.markedName("minos-probe-" + UUID.randomUUID()));
        try {
            LinuxCgroupJob job = configure(probe, Limits.DEFAULT);
            job.close();
            return true;
        } catch (IOException | RuntimeException exception) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "MINOS Linux cgroup capability probe failed: " + describeFailure(exception, root));
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
                throw new IOException("cgroup ownership file " + PROCS_FILE + " is missing in cgroup " + job.name());
            }
            job.requireKillSwitch();
            return job;
        } catch (IOException | RuntimeException failure) {
            discardUnstartedCgroup(directory, failure);
            throw failure;
        }
    }

    /**
     * Validates a caller-provided job name and stamps it with the ownership mark of this process.
     *
     * <p>The safety bound applies to the caller's name (at most 96 characters); the mark adds at most
     * {@link CgroupJobOwnership#MAX_SUFFIX_LENGTH} characters, keeping the directory name well below
     * the 255-byte filesystem limit.</p>
     */
    static String markedJobName(String name) throws IOException {
        String safe = Objects.requireNonNull(name, "name");
        if (!SAFE_JOB_NAME.matcher(safe).matches()) {
            throw new IOException("cgroup job name is not a safe single path segment: " + name);
        }
        return CgroupJobOwnership.CURRENT.markedName(safe);
    }

    private static Path jobDirectory(Path root, String name) throws IOException {
        Path normalizedRoot = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        String safe = markedJobName(name);
        Path directory = normalizedRoot.resolve(safe).toAbsolutePath().normalize();
        if (!directory.startsWith(normalizedRoot)
                || directory.equals(normalizedRoot)
                || !directory.startsWith(CGROUP_MOUNT)) {
            throw new IOException("cgroup job " + safe + " escapes the delegated cgroup root or the cgroup mount");
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
            throw new IOException("kernel " + KILL_FILE + " control is missing in cgroup " + name());
        }
        return killSwitch;
    }

    private IllegalStateException containmentFailure(String detail, Throwable cause) {
        String message = "strong cgroup containment cleanup failed for cgroup " + name() + ": " + detail;
        return cause == null
                ? new IllegalStateException(message)
                : new IllegalStateException(message, redactCause(cause, directory.getParent()));
    }

    /**
     * Returns the cause to attach to a failure leaving this class, without any absolute path.
     *
     * <p>A failure whose chain (message, causes, suppressed failures) carries no path is attached as
     * it is, and an {@link InterruptedException} always is: the orchestration looks for it in the
     * cause chain to persist an interrupted, resumable run. Only a failure that carries a path is
     * re-expressed as a {@link RedactedCause}, which keeps its stack trace, the fully qualified name
     * of its class, and its causes and suppressed failures, each redacted the same way.</p>
     */
    static Throwable redactCause(Throwable cause, Path root) {
        return redactCause(cause, root, identitySet(), 0);
    }

    private static Throwable redactCause(Throwable cause, Path root, Set<Throwable> visiting, int depth) {
        if (cause == null || cause instanceof InterruptedException) return cause;
        if (!carriesPath(cause, root, identitySet(), 0)) return cause;
        if (depth >= MAX_REDACTION_DEPTH || !visiting.add(cause)) {
            // A pathological (cyclic or very deep) chain is cut rather than leaked.
            return new RedactedCause(cause, root, null);
        }
        Throwable next = cause.getCause() == cause ? null : cause.getCause();
        RedactedCause copy = new RedactedCause(cause, root, redactCause(next, root, visiting, depth + 1));
        for (Throwable suppressed : cause.getSuppressed()) {
            copy.addSuppressed(redactCause(suppressed, root, visiting, depth + 1));
        }
        return copy;
    }

    private static boolean carriesPath(Throwable failure, Path root, Set<Throwable> seen, int depth) {
        if (failure == null || !seen.add(failure)) return false;
        if (depth >= MAX_REDACTION_DEPTH) return true;
        String message = failure.getMessage();
        if (message != null && !redactPaths(message, root).equals(message)) return true;
        if (failure.getCause() != failure && carriesPath(failure.getCause(), root, seen, depth + 1)) return true;
        for (Throwable suppressed : failure.getSuppressed()) {
            if (carriesPath(suppressed, root, seen, depth + 1)) return true;
        }
        return false;
    }

    private static Set<Throwable> identitySet() {
        return java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    }

    /** The cgroup directory name, which is how diagnostics designate this job (never by absolute path). */
    String name() {
        return String.valueOf(directory.getFileName());
    }

    /** The delegated root as a cgroup path relative to the cgroup mount (its name outside the mount). */
    static String displayRoot(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        if (normalized.equals(CGROUP_MOUNT)) return "/";
        if (normalized.startsWith(CGROUP_MOUNT)) return CGROUP_MOUNT.relativize(normalized).toString();
        return String.valueOf(normalized.getFileName());
    }

    /**
     * Renders a failure and its causes for the journal with every absolute path rewritten relative to
     * the delegated root (or to the cgroup mount). The failure itself is never attached to a record,
     * because a JDK file-system exception carries the absolute path in its message.
     */
    static String describeFailure(Throwable failure, Path root) {
        StringBuilder text = new StringBuilder();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_DESCRIBED_CAUSES; depth++) {
            if (depth > 0) text.append("; caused by ");
            text.append(current instanceof RedactedCause
                    ? current.getMessage()
                    : current.getClass().getSimpleName() + ": " + redactPaths(current.getMessage(), root));
            current = current.getCause() == current ? null : current.getCause();
        }
        return text.toString();
    }

    /** Rewrites the delegated root, then the cgroup mount, out of a diagnostic text. */
    static String redactPaths(String text, Path root) {
        if (text == null) return "";
        String result = text;
        if (root != null) {
            Path normalized = root.toAbsolutePath().normalize();
            String absolute = normalized.toString();
            result = result.replace(absolute + normalized.getFileSystem().getSeparator(), "")
                    .replace(absolute, displayRoot(normalized));
        }
        String mount = CGROUP_MOUNT.toString();
        return result.replace(mount + CGROUP_MOUNT.getFileSystem().getSeparator(), "").replace(mount, "/");
    }

    /**
     * A failure that carried an absolute path, re-expressed without it: its message starts with the
     * fully qualified name of the original class, followed by the original message rewritten relative
     * to the delegated root; its stack trace is the original one. Causes and suppressed failures are
     * attached by {@link #redactCause}.
     */
    static final class RedactedCause extends Exception {
        private static final long serialVersionUID = 1L;
        private final String originalClassName;

        RedactedCause(Throwable original, Path root, Throwable redactedCause) {
            super(original.getClass().getName()
                    + (original.getMessage() == null ? "" : ": " + redactPaths(original.getMessage(), root)),
                    redactedCause, true, true);
            this.originalClassName = original.getClass().getName();
            setStackTrace(original.getStackTrace());
        }

        /** Fully qualified name of the class of the failure this one stands for. */
        String originalClassName() {
            return originalClassName;
        }
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
