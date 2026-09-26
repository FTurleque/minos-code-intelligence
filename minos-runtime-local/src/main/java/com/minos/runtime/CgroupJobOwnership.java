package com.minos.runtime;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
 * <p>Format: {@code <job>.own-<pid>-<startEpochMillis>-<token>} where {@code pid} is the owning MINOS
 * process, {@code startEpochMillis} its start instant as reported by the kernel ({@code 0} when
 * unknown) and {@code token} an eight-hex-digit instance token generated once per JVM. The start
 * instant distinguishes a live owner from an unrelated process that reused its PID; the token lets
 * a JVM recognize its own cgroups without consulting the process table.</p>
 */
final class CgroupJobOwnership {

    static final String MARK_SEPARATOR = ".own-";
    /** Upper bound of {@link Mark#suffix()}: separator, 10-digit pid, 19-digit instant, 8-char token. */
    static final int MAX_SUFFIX_LENGTH = MARK_SEPARATOR.length() + 10 + 1 + 19 + 1 + 8;
    /** Tolerance absorbing the boot-time rounding two JVMs may apply when computing a start instant. */
    static final long START_INSTANT_TOLERANCE_MILLIS = 2_000L;
    private static final long MAX_PID = 9_999_999_999L;

    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{8}");
    private static final Pattern MARKED_NAME = Pattern.compile(
            "^(?<job>[A-Za-z0-9][A-Za-z0-9._-]*)\\.own-(?<pid>[0-9]{1,10})-(?<start>[0-9]{1,19})-(?<token>[0-9a-f]{8})$");

    /** The mark of the running MINOS process, fixed for the lifetime of the JVM. */
    static final Mark CURRENT = Mark.of(ProcessHandle.current(), Mark.newToken());

    private CgroupJobOwnership() {
    }

    /** Owner PID, owner start instant (epoch millis, {@code 0} when unknown) and per-JVM instance token. */
    record Mark(long pid, long startEpochMillis, String token) {

        Mark {
            if (pid <= 0L || pid > MAX_PID) throw new IllegalArgumentException("owner pid is out of range");
            if (startEpochMillis < 0L) throw new IllegalArgumentException("start instant must not be negative");
            if (!TOKEN.matcher(Objects.requireNonNull(token, "token")).matches()) {
                throw new IllegalArgumentException("instance token must be eight lowercase hex digits");
            }
        }

        static Mark of(ProcessHandle owner, String token) {
            long start = owner.info().startInstant().map(Instant::toEpochMilli).filter(v -> v > 0L).orElse(0L);
            return new Mark(owner.pid(), start, token);
        }

        static String newToken() {
            return UUID.randomUUID().toString().substring(0, 8).toLowerCase(Locale.ROOT);
        }

        /** Parses the mark out of a cgroup directory name; empty for unmarked (legacy or foreign) names. */
        static Optional<Mark> parse(String directoryName) {
            Matcher matcher = MARKED_NAME.matcher(Objects.requireNonNull(directoryName, "directoryName"));
            if (!matcher.matches()) return Optional.empty();
            try {
                return Optional.of(new Mark(
                        Long.parseLong(matcher.group("pid")),
                        Long.parseLong(matcher.group("start")),
                        matcher.group("token")));
            } catch (IllegalArgumentException malformed) {
                return Optional.empty();
            }
        }

        String suffix() {
            return MARK_SEPARATOR + pid + "-" + startEpochMillis + "-" + token;
        }

        /** Appends this mark to a validated single-segment job name. */
        String markedName(String jobName) {
            return Objects.requireNonNull(jobName, "jobName") + suffix();
        }
    }

    /** A live process found for an owner PID, with the start instant the kernel reports for it. */
    record LiveProcess(long pid, Optional<Instant> startInstant) {
        LiveProcess {
            Objects.requireNonNull(startInstant, "startInstant");
        }
    }

    /** Resolves whether a PID currently designates a live process; a seam for host-independent tests. */
    @FunctionalInterface
    interface OwnerLookup {

        OwnerLookup SYSTEM = pid -> ProcessHandle.of(pid)
                .filter(ProcessHandle::isAlive)
                .map(handle -> new LiveProcess(pid, handle.info().startInstant()));

        /** Empty when no live process currently carries the PID. */
        Optional<LiveProcess> find(long pid);
    }

    enum Decision { RECLAIM, LEAVE }

    /** Outcome of the sweep decision with the reason MINOS logs for it (never contains a path). */
    record Verdict(Decision decision, String reason) {
        Verdict {
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(reason, "reason");
        }

        boolean reclaim() {
            return decision == Decision.RECLAIM;
        }
    }

    /**
     * Decides whether a discovered {@code minos-*} cgroup may be reclaimed.
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
        Optional<LiveProcess> live = owners.find(owner.pid());
        if (live.isEmpty()) {
            return new Verdict(Decision.RECLAIM, "owner pid " + owner.pid() + " is no longer alive");
        }
        if (owner.startEpochMillis() == 0L) {
            return new Verdict(Decision.LEAVE, "owner pid " + owner.pid()
                    + " is alive and the mark carries no start instant to rule out pid reuse");
        }
        Optional<Instant> liveStart = live.orElseThrow().startInstant();
        if (liveStart.isEmpty()) {
            return new Verdict(Decision.LEAVE, "owner pid " + owner.pid()
                    + " is alive and its start instant is unavailable to rule out pid reuse");
        }
        long drift = Math.abs(liveStart.orElseThrow().toEpochMilli() - owner.startEpochMillis());
        if (drift > START_INSTANT_TOLERANCE_MILLIS) {
            return new Verdict(Decision.RECLAIM, "owner pid " + owner.pid()
                    + " was reused by another process (start instant differs by " + drift + " ms)");
        }
        return new Verdict(Decision.LEAVE, "cgroup belongs to live MINOS process " + owner.pid());
    }
}
