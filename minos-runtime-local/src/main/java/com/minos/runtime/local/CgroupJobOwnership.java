package com.minos.runtime.local;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
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
 * sweep what to do with a discovered cgroup: remove it when it is empty, kill and remove it when its
 * owner is PROVEN dead, and otherwise leave it alone.
 *
 * <h2>The default conclusion: when in doubt, do not reclaim</h2>
 *
 * <p>A residue left behind costs memory and {@code pids} of the delegated root, and it is reported with its
 * reason. A live process killed is audit S3 coming back. So the asymmetry is absolute: every branch that
 * kills needs a POSITIVE proof that the owner is dead, and whenever that proof is unavailable, partial or
 * ambiguous the conclusion is {@link Decision#LEAVE}. Unavailable or ambiguous means, among others: the
 * wall clock moved (it is not an input at all); {@code /proc} cannot be read, is partial, belongs to another
 * PID namespace or hides other accounts; {@code /proc/<pid>/stat} cannot be read, is cut or malformed, or has
 * no field 22; the PID exists but its start is unknown; the owner lives in another PID or time namespace; the
 * mark carries no namespace stamp; the cgroup is unmarked and populated. The only positive proofs are a
 * process table that was shown to work and has no such PID, and two complete, comparable start-tick values
 * that differ for a PID that exists. A cgroup that holds no process is removed, never killed.</p>
 *
 * <p>cgroup v2 refuses arbitrary files inside a cgroup directory, so the mark lives in the directory
 * name itself: it is created atomically with the cgroup, disappears with it, survives a crash of its
 * owner and needs no side channel a second MINOS instance would have to locate and trust.</p>
 *
 * <p>Format: {@code <job>.own-<pid>-t<startTicks>-n<pidNamespace>_<timeNamespace>-<token>} where
 * {@code pid} is the owning MINOS process, {@code startTicks} its start time in clock ticks since boot as
 * the kernel reports it in field 22 of {@code /proc/<pid>/stat} ({@code 0} when unknown), the two namespace
 * numbers the inodes of {@code /proc/<pid>/ns/pid} and {@code ns/time} of the owner (the stamp is omitted when
 * the PID namespace cannot be read) and {@code token} an eight-hex-digit instance token generated once per
 * JVM. The start ticks distinguish a live owner from an unrelated process that reused its PID; the token
 * lets a JVM recognize its own cgroups without consulting the process table.</p>
 *
 * <p>A PID, and the start ticks read for it, only mean something inside the namespaces they were observed
 * in. A sweeper in another PID namespace reads another process table, where the owner's PID looks absent or
 * designates another process; a sweeper in another time namespace reads start ticks shifted by the boot-time
 * offset of its namespace. Both would conclude a death that did not happen. The stamp is how the sweeper
 * knows it is not in that situation: a mark whose namespaces differ from its own, or that carries none, is
 * left alone.</p>
 *
 * <p>The start ticks are counted on the boot clock, which a wall-clock step (NTP, {@code date -s})
 * never moves, and are fixed at process creation: every reader obtains the same value for the same
 * process, so the comparison is exact. The previous format,
 * {@code <job>.own-<pid>-<startEpochMillis>-<token>}, derived the start from the wall clock
 * ({@code ProcessHandle.Info.startInstant}, based on {@code btime}): two JVMs on either side of a clock
 * step computed different instants for the SAME process and a sweep concluded to a PID reuse, killing
 * a live MINOS (audit R2). Such legacy marks are still recognized, but never reclaimed: a wall-clock
 * instant and no namespaces prove nothing.</p>
 *
 * <p>The {@code t} prefix and the namespace stamp make the current format unrecognizable to the previous
 * parsers, which read it as an unmarked name and therefore never reclaim a populated cgroup of a newer
 * MINOS.</p>
 */
final class CgroupJobOwnership {

    static final String MARK_SEPARATOR = ".own-";
    /** Distinguishes boot-tick marks from legacy wall-clock marks; the previous parser rejects it. */
    static final String TICKS_PREFIX = "t";
    /** Introduces the namespace stamp: {@code -n<pidNamespace>_<timeNamespace>}. */
    private static final String NAMESPACE_PREFIX = "-n";
    /**
     * Upper bound of {@link Mark#suffix()}: separator, 10-digit pid, {@code t}, 19-digit ticks, {@code -n},
     * two 19-digit namespace inodes joined by {@code _}, 8-char token.
     */
    static final int MAX_SUFFIX_LENGTH = MARK_SEPARATOR.length() + 10 + 1 + TICKS_PREFIX.length() + 19
            + NAMESPACE_PREFIX.length() + 19 + 1 + 19 + 1 + 8;
    /** The kernel process table; {@code /proc/<pid>/stat} carries the start time in clock ticks. */
    static final Path PROC = Path.of("/proc");
    /** 1-based index of {@code starttime} in {@code /proc/<pid>/stat}. */
    private static final int STARTTIME_FIELD = 22;
    /** 1-based index of the first field after {@code comm} ({@code state}). */
    private static final int FIRST_FIELD_AFTER_COMM = 3;
    private static final long MAX_PID = 9_999_999_999L;
    private static final String OWNER_PID = "owner pid ";

    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{8}");
    private static final Pattern NAMESPACE_LINK = Pattern.compile("^[a-z_]+:\\[(?<inode>\\d{1,19})\\]$");
    private static final Pattern MARKED_NAME = Pattern.compile(
            "^(?<job>[A-Za-z0-9][A-Za-z0-9._-]*)\\.own-(?<pid>\\d{1,10})-"
                    + "(?:t(?<ticks>\\d{1,19})(?:-n(?<pidns>[1-9]\\d{0,18})_(?<timens>\\d{1,19}))?"
                    + "|(?<legacy>\\d{1,19}))-(?<token>[0-9a-f]{8})$");

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
                if (ticks == null) return Optional.of(legacyWallClock(pid, Long.parseLong(matcher.group("legacy")), token));
                String pidNamespace = matcher.group("pidns");
                Namespaces stamp = pidNamespace == null
                        ? Namespaces.UNKNOWN
                        : new Namespaces(Long.parseLong(pidNamespace), Long.parseLong(matcher.group("timens")));
                return Optional.of(new Mark(pid, Long.parseLong(ticks), token).withNamespaces(stamp));
            } catch (IllegalArgumentException malformed) {
                return Optional.empty();
            }
        }

        String suffix() {
            String startPart = clock == StartClock.BOOT_TICKS
                    ? TICKS_PREFIX + start + namespaceSuffix()
                    : Long.toString(start);
            return MARK_SEPARATOR + pid + "-" + startPart + "-" + token;
        }

        private String namespaceSuffix() {
            return namespaces.known() ? NAMESPACE_PREFIX + namespaces.pid() + "_" + namespaces.time() : "";
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

        /**
         * The process table of this host, as seen for the cgroup at {@code cgroup}: the owner of a cgroup
         * is an account, and whether this account can see its processes depends on that account.
         */
        static OwnerLookup system(Path cgroup) {
            Objects.requireNonNull(cgroup, "cgroup");
            return new ProcessTable(PROC, ProcessHandle.current().pid(), () -> sameAccount(PROC, cgroup));
        }

        OwnerStatus find(long pid);
    }

    /** True when the cgroup was created by the account this process runs as; false when that cannot be told. */
    static boolean sameAccount(Path proc, Path cgroup) {
        try {
            return Files.getOwner(cgroup, LinkOption.NOFOLLOW_LINKS).equals(Files.getOwner(proc.resolve("self")));
        } catch (IOException | RuntimeException unknown) {
            return false;
        }
    }

    /**
     * A process table read from a {@code /proc} directory, answering only what it can prove.
     *
     * <p>"This PID is gone" is a claim about the table, and it is made only about a table that has been
     * shown to work: {@code self/stat} is readable and describes this very process, so the table is
     * readable and belongs to this PID namespace; the entry of the PID is reported absent by the file
     * system itself (and not by a read that failed for another reason: no descriptor left, no permission);
     * and the table does not hide other accounts' processes, or the owner is this account, whose processes
     * it always shows. The JDK's {@code ProcessHandle.of} makes none of these checks: it answers "no such
     * process" whenever its own read of {@code /proc/<pid>/stat} fails, for a live process too.</p>
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
            String self;
            try {
                self = Files.readString(proc.resolve("self").resolve("stat"), StandardCharsets.ISO_8859_1);
            } catch (IOException | RuntimeException unreadable) {
                return OwnerStatus.unverifiable("the process table cannot be read ("
                        + unreadable.getClass().getSimpleName() + ")");
            }
            if (!self.startsWith(ownPid + " ")) {
                return OwnerStatus.unverifiable("the process table is not the one of this PID namespace");
            }
            Path entry = proc.resolve(Long.toString(pid));
            String stat;
            try {
                stat = Files.readString(entry.resolve("stat"), StandardCharsets.ISO_8859_1);
            } catch (NoSuchFileException absent) {
                if (!Files.notExists(entry, LinkOption.NOFOLLOW_LINKS)) {
                    return OwnerStatus.unverifiable("the process entry exists but its stat is missing");
                }
                return goneUnlessHidden();
            } catch (IOException | RuntimeException unreadable) {
                return OwnerStatus.unverifiable("the process stat cannot be read ("
                        + unreadable.getClass().getSimpleName() + ")");
            }
            return OwnerStatus.present(parseStartTicks(stat));
        }

        private OwnerStatus goneUnlessHidden() {
            if (hidesOtherAccounts() && !sameAccount.getAsBoolean()) {
                return OwnerStatus.unverifiable("the process table hides the processes of other accounts"
                        + " (hidepid) and the owner may be one");
            }
            return OwnerStatus.gone();
        }

        /** True unless the mount options show the table hides nothing; unreadable mount options are doubt. */
        private boolean hidesOtherAccounts() {
            List<String> mounts;
            try {
                mounts = Files.readAllLines(proc.resolve("self").resolve("mountinfo"), StandardCharsets.ISO_8859_1);
            } catch (IOException | RuntimeException unreadable) {
                return true;
            }
            for (String mount : mounts) {
                int separator = mount.indexOf(" - ");
                if (separator < 0) continue;
                String[] filesystem = mount.substring(separator + 3).split(" ");
                if (filesystem.length < 3 || !"proc".equals(filesystem[0])) continue;
                for (String option : filesystem[2].split(",")) {
                    if (!option.startsWith("hidepid=")) continue;
                    String value = option.substring("hidepid=".length());
                    if (!"0".equals(value) && !"off".equals(value)) return true;
                }
            }
            return false;
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
     * Decides what to do with a discovered {@code minos-*} cgroup. The conclusion is {@link Decision#LEAVE}
     * unless one of these holds (see the class documentation: when in doubt, do not reclaim):
     * <ul>
     *   <li>the cgroup is unmarked and holds no process in it or below it: {@link Decision#REMOVE_EMPTY},
     *       which never kills;</li>
     *   <li>the owner lives in the same PID and time namespaces as the sweeper and the process table,
     *       proven readable, has no such PID: {@link Decision#RECLAIM};</li>
     *   <li>same namespaces, the PID exists and its complete start ticks differ from the mark's: the PID was
     *       reused, {@link Decision#RECLAIM}.</li>
     * </ul>
     * Everything else is left intact: this sweeper's own cgroups, a live owner, a legacy or unstamped mark, an
     * owner of another namespace, an owner the process table cannot verify, a PID whose start is unknown, an
     * unmarked cgroup that holds a process. The wall clock never takes part in the decision.
     *
     * @param mark           the ownership mark parsed from the directory name, empty for unmarked names
     * @param self           the mark of the sweeping MINOS process
     * @param owners         process-table lookup for the owner PID
     * @param aliveProcesses number of processes currently inside the cgroup and the cgroups below it, read
     *                       lazily and only when the decision needs it; may throw a containment failure the
     *                       caller propagates
     */
    static Verdict decide(Optional<Mark> mark, Mark self, OwnerLookup owners, LongSupplier aliveProcesses) {
        Objects.requireNonNull(mark, "mark");
        Objects.requireNonNull(self, "self");
        Objects.requireNonNull(owners, "owners");
        Objects.requireNonNull(aliveProcesses, "aliveProcesses");
        if (mark.isEmpty()) {
            long alive = aliveProcesses.getAsLong();
            if (alive == 0L) return new Verdict(Decision.REMOVE_EMPTY, "unmarked cgroup holds no process");
            return new Verdict(Decision.LEAVE, "unmarked cgroup still holds " + alive
                    + " process(es) and its owner cannot be identified");
        }
        Mark owner = mark.orElseThrow();
        if (owner.token().equals(self.token())) {
            return new Verdict(Decision.LEAVE, "cgroup belongs to this MINOS instance");
        }
        if (owner.clock() == StartClock.LEGACY_WALL_CLOCK) {
            return new Verdict(Decision.LEAVE, "legacy mark of owner pid " + owner.pid() + " carries a wall-clock"
                    + " start instant and no namespaces, which prove nothing about the owner");
        }
        if (!self.namespaces().known()) {
            return new Verdict(Decision.LEAVE, "the PID namespace of this process cannot be identified, so owner pid "
                    + owner.pid() + " cannot be shown to live in it");
        }
        if (!owner.namespaces().known()) {
            return new Verdict(Decision.LEAVE, "the mark of owner pid " + owner.pid() + " carries no namespace stamp"
                    + " (written by an earlier build), so its pid cannot be looked up here");
        }
        if (!owner.namespaces().equals(self.namespaces())) {
            return new Verdict(Decision.LEAVE, OWNER_PID + owner.pid() + " lives in another PID or time namespace:"
                    + " its pid and start ticks mean nothing here");
        }
        OwnerStatus status = owners.find(owner.pid());
        if (status.presence() == OwnerStatus.Presence.UNVERIFIABLE) {
            return new Verdict(Decision.LEAVE, OWNER_PID + owner.pid() + " cannot be verified: "
                    + status.reason());
        }
        if (status.presence() == OwnerStatus.Presence.GONE) {
            return new Verdict(Decision.RECLAIM, OWNER_PID + owner.pid()
                    + " is absent from a process table proven readable, it is no longer alive");
        }
        if (owner.start() == 0L) {
            return new Verdict(Decision.LEAVE, OWNER_PID + owner.pid()
                    + " is alive and the mark carries no start ticks to rule out pid reuse");
        }
        OptionalLong liveTicks = status.startTicks();
        if (liveTicks.isEmpty()) {
            return new Verdict(Decision.LEAVE, OWNER_PID + owner.pid()
                    + " is alive and its start ticks are unavailable to rule out pid reuse");
        }
        if (liveTicks.getAsLong() != owner.start()) {
            return new Verdict(Decision.RECLAIM, OWNER_PID + owner.pid()
                    + " was reused by another process (kernel start ticks " + liveTicks.getAsLong()
                    + " instead of " + owner.start() + ")");
        }
        return new Verdict(Decision.LEAVE, "cgroup belongs to live MINOS process " + owner.pid());
    }
}
