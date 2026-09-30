package com.minos.runtime.local;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ownership mark carried by every cgroup MINOS creates, and the pure decision that tells a stale-job
 * sweep whether a discovered cgroup belongs to a dead MINOS process and may therefore be reclaimed.
 *
 * <p>cgroup v2 refuses arbitrary files inside a cgroup directory, so the mark lives in the directory
 * name itself: it is created atomically with the cgroup, disappears with it, survives a crash of its
 * owner and needs no side channel a second MINOS instance would have to locate and trust.</p>
 *
 * <p>Format: {@code <job>.own-<pid>-t<startTicks>-<token>} where {@code pid} is the owning MINOS
 * process, {@code startTicks} its start time in clock ticks since boot as the kernel reports it in
 * field 22 of {@code /proc/<pid>/stat} ({@code 0} when unknown) and {@code token} an eight-hex-digit
 * instance token generated once per JVM. The start ticks distinguish a live owner from an unrelated
 * process that reused its PID; the token lets a JVM recognize its own cgroups without consulting the
 * process table.</p>
 *
 * <p>The start ticks are counted on the boot clock, which a wall-clock step (NTP, {@code date -s})
 * never moves, and are fixed at process creation: every reader obtains the same value for the same
 * process, so the comparison is exact. The previous format,
 * {@code <job>.own-<pid>-<startEpochMillis>-<token>}, derived the start from the wall clock
 * ({@code ProcessHandle.Info.startInstant}, based on {@code btime}): two JVMs on either side of a clock
 * step computed different instants for the SAME process and a sweep concluded to a PID reuse, killing
 * a live MINOS (audit R2). Such legacy marks are still recognized, but only a dead owner PID reclaims
 * them: a live owner is never killed on a start-instant difference.</p>
 *
 * <p>The {@code t} prefix makes the current format unrecognizable to the previous parser, which reads
 * it as an unmarked name and therefore never reclaims a populated cgroup of a newer MINOS.</p>
 */
final class CgroupJobOwnership {

    static final String MARK_SEPARATOR = ".own-";
    /** Distinguishes boot-tick marks from legacy wall-clock marks; the previous parser rejects it. */
    static final String TICKS_PREFIX = "t";
    /** Upper bound of {@link Mark#suffix()}: separator, 10-digit pid, {@code t}, 19-digit ticks, 8-char token. */
    static final int MAX_SUFFIX_LENGTH = MARK_SEPARATOR.length() + 10 + 1 + TICKS_PREFIX.length() + 19 + 1 + 8;
    /** The kernel process table; {@code /proc/<pid>/stat} carries the start time in clock ticks. */
    static final Path PROC = Path.of("/proc");
    /** 1-based index of {@code starttime} in {@code /proc/<pid>/stat}. */
    private static final int STARTTIME_FIELD = 22;
    /** 1-based index of the first field after {@code comm} ({@code state}). */
    private static final int FIRST_FIELD_AFTER_COMM = 3;
    private static final long MAX_PID = 9_999_999_999L;

    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{8}");
    private static final Pattern NAMESPACE_LINK = Pattern.compile("^[a-z_]+:\\[(?<inode>[0-9]{1,19})\\]$");
    private static final Pattern MARKED_NAME = Pattern.compile(
            "^(?<job>[A-Za-z0-9][A-Za-z0-9._-]*)\\.own-(?<pid>[0-9]{1,10})-"
                    + "(?:t(?<ticks>[0-9]{1,19})|(?<legacy>[0-9]{1,19}))-(?<token>[0-9a-f]{8})$");

    /** The mark of the running MINOS process, fixed for the lifetime of the JVM. */
    static final Mark CURRENT = Mark.of(ProcessHandle.current(), Mark.newToken());

    private CgroupJobOwnership() {
    }

    /** Clock the start value of a mark is expressed in. */
    enum StartClock {
        /** Clock ticks since boot ({@code /proc/<pid>/stat} field 22): exact, immune to wall-clock steps. */
        BOOT_TICKS,
        /** Epoch milliseconds derived from the wall clock by the previous format: never proves a PID reuse. */
        LEGACY_WALL_CLOCK
    }

    /**
     * Identity of the PID and time namespaces a process lives in, as the inode numbers of
     * {@code /proc/<pid>/ns/pid} and {@code /proc/<pid>/ns/time}. A PID, and the start ticks read for it,
     * only mean something inside the namespaces they were observed in.
     *
     * @param pid  inode of the PID namespace; {@code 0} when unknown
     * @param time inode of the time namespace; {@code 0} when the kernel has none (before Linux 5.6) or it
     *             cannot be read
     */
    record Namespaces(long pid, long time) {

        /** No namespace identity: what a mark written before namespaces were stamped carries. */
        static final Namespaces UNKNOWN = new Namespaces(0L, 0L);

        Namespaces {
            if (pid < 0L || time < 0L) throw new IllegalArgumentException("namespace inode must not be negative");
        }

        /** True when the PID namespace is identified; a mark or a process without it proves nothing. */
        boolean known() {
            return pid > 0L;
        }

        /** Reads the namespaces of a process; {@link #UNKNOWN} when its PID namespace cannot be read. */
        static Namespaces read(Path proc, long pid) {
            Path namespaces = proc.resolve(Long.toString(pid)).resolve("ns");
            OptionalLong pidNamespace = inode(namespaces.resolve("pid"));
            if (pidNamespace.isEmpty()) return UNKNOWN;
            return new Namespaces(pidNamespace.getAsLong(), inode(namespaces.resolve("time")).orElse(0L));
        }

        private static OptionalLong inode(Path link) {
            try {
                return parseInode(Files.readSymbolicLink(link).toString());
            } catch (IOException | RuntimeException unreadable) {
                return OptionalLong.empty();
            }
        }

        /** Extracts the inode of a namespace link target such as {@code pid:[4026531836]}. */
        static OptionalLong parseInode(String target) {
            if (target == null) return OptionalLong.empty();
            Matcher matcher = NAMESPACE_LINK.matcher(target);
            if (!matcher.matches()) return OptionalLong.empty();
            try {
                long inode = Long.parseLong(matcher.group("inode"));
                return inode > 0L ? OptionalLong.of(inode) : OptionalLong.empty();
            } catch (NumberFormatException overflow) {
                return OptionalLong.empty();
            }
        }
    }

    /**
     * Owner PID, owner start ({@code 0} when unknown) in the given clock, the namespaces the owner lived
     * in, and per-JVM instance token.
     */
    record Mark(long pid, long start, StartClock clock, Namespaces namespaces, String token) {

        Mark {
            if (pid <= 0L || pid > MAX_PID) throw new IllegalArgumentException("owner pid is out of range");
            if (start < 0L) throw new IllegalArgumentException("start must not be negative");
            Objects.requireNonNull(clock, "clock");
            Objects.requireNonNull(namespaces, "namespaces");
            if (!TOKEN.matcher(Objects.requireNonNull(token, "token")).matches()) {
                throw new IllegalArgumentException("instance token must be eight lowercase hex digits");
            }
        }

        /** A current-format mark without namespace identity: start expressed in kernel ticks since boot. */
        Mark(long pid, long startTicks, String token) {
            this(pid, startTicks, StartClock.BOOT_TICKS, Namespaces.UNKNOWN, token);
        }

        /** A mark in the previous, wall-clock format; MINOS only parses such marks, it never writes them. */
        static Mark legacyWallClock(long pid, long startEpochMillis, String token) {
            return new Mark(pid, startEpochMillis, StartClock.LEGACY_WALL_CLOCK, Namespaces.UNKNOWN, token);
        }

        /** The same mark stamped with the namespaces its owner lives in. */
        Mark withNamespaces(Namespaces owned) {
            return new Mark(pid, start, clock, owned, token);
        }

        static Mark of(ProcessHandle owner, String token) {
            return new Mark(owner.pid(), startTicks(PROC, owner.pid()).orElse(0L), token)
                    .withNamespaces(Namespaces.read(PROC, owner.pid()));
        }

        static String newToken() {
            return UUID.randomUUID().toString().substring(0, 8).toLowerCase(Locale.ROOT);
        }

        /** Parses the mark out of a cgroup directory name; empty for unmarked (legacy or foreign) names. */
        static Optional<Mark> parse(String directoryName) {
            Matcher matcher = MARKED_NAME.matcher(Objects.requireNonNull(directoryName, "directoryName"));
            if (!matcher.matches()) return Optional.empty();
            try {
                long pid = Long.parseLong(matcher.group("pid"));
                String token = matcher.group("token");
                String ticks = matcher.group("ticks");
                return Optional.of(ticks != null
                        ? new Mark(pid, Long.parseLong(ticks), token)
                        : legacyWallClock(pid, Long.parseLong(matcher.group("legacy")), token));
            } catch (IllegalArgumentException malformed) {
                return Optional.empty();
            }
        }

        String suffix() {
            String startPart = clock == StartClock.BOOT_TICKS ? TICKS_PREFIX + start : Long.toString(start);
            return MARK_SEPARATOR + pid + "-" + startPart + "-" + token;
        }

        /** Appends this mark to a validated single-segment job name. */
        String markedName(String jobName) {
            return Objects.requireNonNull(jobName, "jobName") + suffix();
        }
    }

    /**
     * Reads the start time, in clock ticks since boot, of a process from {@code <proc>/<pid>/stat}.
     * Empty when the file cannot be read or does not have the expected shape: callers must treat that
     * as "unknown", never as "dead".
     */
    static OptionalLong startTicks(Path proc, long pid) {
        try {
            return parseStartTicks(Files.readString(
                    proc.resolve(Long.toString(pid)).resolve("stat"), StandardCharsets.ISO_8859_1));
        } catch (IOException | RuntimeException unreadable) {
            return OptionalLong.empty();
        }
    }

    /**
     * Extracts field 22 ({@code starttime}) from the content of a {@code /proc/<pid>/stat} file.
     *
     * <p>Field 2 ({@code comm}) is enclosed in parentheses but may itself contain spaces and
     * parentheses, so the fields are counted after the LAST closing parenthesis.</p>
     *
     * <p>The value is trusted only when the record is complete: it ends with its newline and a field
     * follows field 22. A read cut inside field 22 leaves a shorter number that is still a number, and a
     * shorter number reads as a different start, that is as a PID reuse. Anything less than a complete
     * record is unknown, and unknown never proves an owner dead.</p>
     */
    static OptionalLong parseStartTicks(String stat) {
        if (stat == null || !stat.endsWith("\n")) return OptionalLong.empty();
        int commEnd = stat.lastIndexOf(')');
        if (commEnd < 0) return OptionalLong.empty();
        String remainder = stat.substring(commEnd + 1).trim();
        if (remainder.isEmpty()) return OptionalLong.empty();
        String[] fields = remainder.split("\\s+");
        int index = STARTTIME_FIELD - FIRST_FIELD_AFTER_COMM;
        if (fields.length <= index + 1) return OptionalLong.empty();
        try {
            long ticks = Long.parseLong(fields[index]);
            return ticks > 0L ? OptionalLong.of(ticks) : OptionalLong.empty();
        } catch (NumberFormatException malformed) {
            return OptionalLong.empty();
        }
    }

    /**
     * What the process table says about an owner PID.
     *
     * @param presence   {@link Presence#GONE} only when the table was proven readable and has no such process
     * @param startTicks kernel start ticks of the process when {@code presence} is {@link Presence#PRESENT}
     * @param reason     why the table could not answer, when {@code presence} is {@link Presence#UNVERIFIABLE}
     */
    record OwnerStatus(Presence presence, OptionalLong startTicks, String reason) {

        enum Presence { GONE, PRESENT, UNVERIFIABLE }

        OwnerStatus {
            Objects.requireNonNull(presence, "presence");
            Objects.requireNonNull(startTicks, "startTicks");
            Objects.requireNonNull(reason, "reason");
        }

        static OwnerStatus gone() {
            return new OwnerStatus(Presence.GONE, OptionalLong.empty(), "");
        }

        static OwnerStatus present(OptionalLong startTicks) {
            return new OwnerStatus(Presence.PRESENT, startTicks, "");
        }

        static OwnerStatus present(long startTicks) {
            return present(OptionalLong.of(startTicks));
        }

        static OwnerStatus unverifiable(String reason) {
            return new OwnerStatus(Presence.UNVERIFIABLE, OptionalLong.empty(), reason);
        }
    }

    /** Resolves what the process table says about a PID; a seam for host-independent tests. */
    @FunctionalInterface
    interface OwnerLookup {

        /** The process table of this host, as seen for the cgroup at {@code cgroup}. */
        static OwnerLookup system(Path cgroup) {
            Objects.requireNonNull(cgroup, "cgroup");
            return pid -> ProcessHandle.of(pid)
                    .filter(ProcessHandle::isAlive)
                    .map(handle -> OwnerStatus.present(startTicks(PROC, pid)))
                    .orElseGet(OwnerStatus::gone);
        }

        OwnerStatus find(long pid);
    }

    /**
     * A process table read from a {@code /proc} directory.
     *
     * @param proc        the process table directory ({@code /proc}; a temporary directory in tests)
     * @param ownPid      PID of this process in the PID namespace it lives in
     * @param sameAccount whether the processes of the cgroup owner are visible to this account even when
     *                    the table hides those of other accounts
     */
    record ProcessTable(Path proc, long ownPid, BooleanSupplier sameAccount) implements OwnerLookup {

        ProcessTable {
            Objects.requireNonNull(proc, "proc");
            Objects.requireNonNull(sameAccount, "sameAccount");
        }

        @Override
        public OwnerStatus find(long pid) {
            try {
                String stat = Files.readString(
                        proc.resolve(Long.toString(pid)).resolve("stat"), StandardCharsets.ISO_8859_1);
                return OwnerStatus.present(parseStartTicks(stat));
            } catch (IOException | RuntimeException unreadable) {
                return OwnerStatus.gone();
            }
        }
    }

    /** What the sweep does with a discovered cgroup. */
    enum Decision {
        /** The owner is proven dead: kill what is left in the cgroup, then remove it. */
        RECLAIM,
        /** The cgroup holds no process: remove it, never kill anything in it. */
        REMOVE_EMPTY,
        /** No positive proof of ownership by a dead process: leave the cgroup intact and report it. */
        LEAVE
    }

    /** Outcome of the sweep decision with the reason MINOS logs for it (never contains a path). */
    record Verdict(Decision decision, String reason) {
        Verdict {
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(reason, "reason");
        }

        /** True when the sweep removes the cgroup (killing first only when {@link #kills()}). */
        boolean reclaim() {
            return decision != Decision.LEAVE;
        }

        /** True only when the owner is proven dead; an empty cgroup is removed, never killed. */
        boolean kills() {
            return decision == Decision.RECLAIM;
        }
    }

    /**
     * Decides whether a discovered {@code minos-*} cgroup may be reclaimed.
     *
     * <p>A PID reuse is only ever concluded from two kernel start-tick values that differ, both read
     * on the boot clock; the wall clock never takes part in the decision. Whenever the owner PID is
     * alive and a reuse cannot be proven that way, the cgroup is left intact.</p>
     *
     * @param mark           the ownership mark parsed from the directory name, empty for unmarked names
     * @param self           the mark of the sweeping MINOS process
     * @param owners         process-table lookup for the owner PID
     * @param aliveProcesses number of processes currently inside the cgroup, read lazily and only when the
     *                       decision needs it; may throw a containment failure the caller propagates
     */
    static Verdict decide(Optional<Mark> mark, Mark self, OwnerLookup owners, LongSupplier aliveProcesses) {
        Objects.requireNonNull(mark, "mark");
        Objects.requireNonNull(self, "self");
        Objects.requireNonNull(owners, "owners");
        Objects.requireNonNull(aliveProcesses, "aliveProcesses");
        if (mark.isEmpty()) {
            long alive = aliveProcesses.getAsLong();
            if (alive == 0L) return new Verdict(Decision.RECLAIM, "unmarked cgroup holds no process");
            return new Verdict(Decision.LEAVE, "unmarked cgroup still holds " + alive
                    + " process(es) and its owner cannot be identified");
        }
        Mark owner = mark.orElseThrow();
        if (owner.token().equals(self.token())) {
            return new Verdict(Decision.LEAVE, "cgroup belongs to this MINOS instance");
        }
        OwnerStatus status = owners.find(owner.pid());
        if (status.presence() != OwnerStatus.Presence.PRESENT) {
            return new Verdict(Decision.RECLAIM, "owner pid " + owner.pid() + " is no longer alive");
        }
        if (owner.clock() == StartClock.LEGACY_WALL_CLOCK) {
            return new Verdict(Decision.LEAVE, "owner pid " + owner.pid() + " is alive and its legacy mark"
                    + " carries a wall-clock start instant, which cannot prove a pid reuse");
        }
        if (owner.start() == 0L) {
            return new Verdict(Decision.LEAVE, "owner pid " + owner.pid()
                    + " is alive and the mark carries no start ticks to rule out pid reuse");
        }
        OptionalLong liveTicks = status.startTicks();
        if (liveTicks.isEmpty()) {
            return new Verdict(Decision.LEAVE, "owner pid " + owner.pid()
                    + " is alive and its start ticks are unavailable to rule out pid reuse");
        }
        if (liveTicks.getAsLong() != owner.start()) {
            return new Verdict(Decision.RECLAIM, "owner pid " + owner.pid()
                    + " was reused by another process (kernel start ticks " + liveTicks.getAsLong()
                    + " instead of " + owner.start() + ")");
        }
        return new Verdict(Decision.LEAVE, "cgroup belongs to live MINOS process " + owner.pid());
    }
}
