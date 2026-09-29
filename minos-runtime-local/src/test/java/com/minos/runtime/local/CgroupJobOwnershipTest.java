package com.minos.runtime.local;

import com.minos.runtime.local.CgroupJobOwnership.Decision;
import com.minos.runtime.local.CgroupJobOwnership.LiveProcess;
import com.minos.runtime.local.CgroupJobOwnership.Mark;
import com.minos.runtime.local.CgroupJobOwnership.OwnerLookup;
import com.minos.runtime.local.CgroupJobOwnership.StartClock;
import com.minos.runtime.local.CgroupJobOwnership.Verdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Host-independent proof of the stale-cgroup decision: only a cgroup whose owning MINOS process is
 * dead (PID gone, or reused as proven by kernel start ticks) is reclaimed; the jobs of another live
 * MINOS instance are left intact, whatever the wall clock did.
 */
class CgroupJobOwnershipTest {

    private static final long MAX_PID_VALUE = 9_999_999_999L;
    private static final Mark SELF = new Mark(4_242L, 1_234_567L, "0badcafe");
    private static final Mark OTHER = new Mark(7_777L, 2_345_678L, "deadbeef");
    /** The mark-name pattern of the previous (S3) MINOS release, verbatim: an older MINOS still parses with it. */
    private static final Pattern PREVIOUS_RELEASE_MARKED_NAME = Pattern.compile(
            "^(?<job>[A-Za-z0-9][A-Za-z0-9._-]*)\\.own-(?<pid>[0-9]{1,10})-(?<start>[0-9]{1,19})-(?<token>[0-9a-f]{8})$");
    private static final LongSupplier MEMBERSHIP_MUST_NOT_BE_READ = () -> {
        throw new AssertionError("the decision must not read cgroup membership for a marked cgroup");
    };

    @Test
    void theMarkRoundTripsThroughTheDirectoryName() {
        String name = OTHER.markedName("minos-provider-2b1f0c5e-1d2c-4a4b-9a2e-1234567890ab");

        assertEquals("minos-provider-2b1f0c5e-1d2c-4a4b-9a2e-1234567890ab.own-7777-t2345678-deadbeef", name);
        assertEquals(Optional.of(OTHER), Mark.parse(name));
        assertEquals(StartClock.BOOT_TICKS, Mark.parse(name).orElseThrow().clock());
    }

    @Test
    void aLegacyWallClockMarkIsStillRecognizedAsSuch() {
        String legacyName = "minos-provider-2b1f0c5e.own-7777-1700000100000-deadbeef";

        Mark legacy = Mark.parse(legacyName).orElseThrow();

        assertEquals(Mark.legacyWallClock(7_777L, 1_700_000_100_000L, "deadbeef"), legacy);
        assertEquals(StartClock.LEGACY_WALL_CLOCK, legacy.clock());
        assertEquals(legacyName, legacy.markedName("minos-provider-2b1f0c5e"));
    }

    /**
     * W2: the previous release keeps running next to this one (IDE plugin, MCP server). Its parser must
     * not recognize the current mark, so that it reads a populated cgroup of this release as unmarked,
     * which its decision (identical to the current one for unmarked names) leaves intact.
     */
    @Test
    void thePreviousReleaseCannotMistakeTheCurrentMarkForItsOwnFormat() {
        String current = CgroupJobOwnership.CURRENT.markedName("minos-provider-new");
        String widest = new Mark(MAX_PID_VALUE, Long.MAX_VALUE, "ffffffff").markedName("minos-x");

        assertFalse(PREVIOUS_RELEASE_MARKED_NAME.matcher(current).matches(), current);
        assertFalse(PREVIOUS_RELEASE_MARKED_NAME.matcher(widest).matches(), widest);
        assertFalse(PREVIOUS_RELEASE_MARKED_NAME.matcher(OTHER.markedName("minos-provider-new")).matches());
        assertTrue(PREVIOUS_RELEASE_MARKED_NAME.matcher(
                Mark.legacyWallClock(7_777L, 1_700_000_100_000L, "deadbeef").markedName("minos-x")).matches(),
                "the pattern must be the real previous one: it recognizes its own marks");

        OwnerLookup mustNotBeCalled = pid -> {
            throw new AssertionError("an unmarked cgroup has no owner pid to look up");
        };
        Verdict previousReleaseVerdict = CgroupJobOwnership.decide(Optional.empty(), SELF, mustNotBeCalled, () -> 1L);
        assertEquals(Decision.LEAVE, previousReleaseVerdict.decision(), previousReleaseVerdict.reason());
    }

    @Test
    void legacyAndMalformedNamesCarryNoMark() {
        assertTrue(Mark.parse("minos-provider-2b1f0c5e").isEmpty(), "legacy name without mark");
        assertTrue(Mark.parse("minos-controller").isEmpty());
        assertTrue(Mark.parse("minos-x.own-7777-t2345678-DEADBEEF").isEmpty(), "token must be lowercase hex");
        assertTrue(Mark.parse("minos-x.own-0-t2345678-deadbeef").isEmpty(), "pid must be positive");
        assertTrue(Mark.parse("minos-x.own-7777-t2345678-deadbeef/evil").isEmpty());
        assertTrue(Mark.parse(".own-7777-t2345678-deadbeef").isEmpty(), "job part is mandatory");
        assertTrue(Mark.parse("minos-x.own-7777-t-deadbeef").isEmpty(), "ticks are mandatory after the prefix");
        assertTrue(Mark.parse("minos-x.own-7777-tt1-deadbeef").isEmpty());
        assertTrue(Mark.parse("minos-x.own-7777-t9999999999999999999-deadbeef").isEmpty(), "ticks overflow");
    }

    @Test
    void theLongestSafeJobNameStillFitsWithItsMark() throws Exception {
        String longest = "minos-" + "x".repeat(90);
        assertEquals(96, longest.length());

        String marked = LinuxCgroupJob.markedJobName(longest);

        assertEquals(Optional.of(CgroupJobOwnership.CURRENT), Mark.parse(marked));
        assertEquals(45, CgroupJobOwnership.MAX_SUFFIX_LENGTH, "separator 5 + pid 10 + 1 + t 1 + ticks 19 + 1 + token 8");
        assertTrue(marked.length() <= 96 + CgroupJobOwnership.MAX_SUFFIX_LENGTH, marked);
        assertTrue(CgroupJobOwnership.CURRENT.suffix().length() <= CgroupJobOwnership.MAX_SUFFIX_LENGTH);
        Mark widest = new Mark(MAX_PID_VALUE, Long.MAX_VALUE, "ffffffff");
        assertEquals(CgroupJobOwnership.MAX_SUFFIX_LENGTH, widest.suffix().length(), "the bound must be tight");
        assertEquals(Optional.of(widest), Mark.parse(widest.markedName(longest)));
        assertTrue(widest.markedName(longest).length() <= 255, "a directory name never exceeds NAME_MAX");
        assertThrows(IOException.class, () -> LinuxCgroupJob.markedJobName(longest + "x"));
        assertThrows(IOException.class, () -> LinuxCgroupJob.markedJobName("../escape"));
    }

    @Test
    void theCurrentMarkDescribesTheRunningJvm() {
        assertEquals(ProcessHandle.current().pid(), CgroupJobOwnership.CURRENT.pid());
        assertEquals(StartClock.BOOT_TICKS, CgroupJobOwnership.CURRENT.clock());
        assertEquals(CgroupJobOwnership.startTicks(CgroupJobOwnership.PROC, ProcessHandle.current().pid()).orElse(0L),
                CgroupJobOwnership.CURRENT.start());
        assertEquals(Optional.of(CgroupJobOwnership.CURRENT),
                Mark.parse(CgroupJobOwnership.CURRENT.markedName("minos-probe-1")));
        assertThrows(IllegalArgumentException.class, () -> new Mark(1L, 0L, "not-hex!"));
        assertThrows(IllegalArgumentException.class, () -> new Mark(1L, -1L, "deadbeef"));
    }

    @Test
    void theStartTicksAreField22AfterTheLastClosingParenthesisOfProcStat() {
        // A comm with spaces and a ") (" sequence must not shift the field count.
        String tricky = "4242 (evil) (name x) S 1 4242 4242 0 -1 4194560 1234 0 0 0 7 3 0 0 20 0 29 0 "
                + "987654 123456789 4321 18446744073709551615 1 1 0 0 0 0 0 4096 0 0 0 0 17 3 0 0\n";
        String plain = "1 (systemd) S 0 1 1 0 -1 4194560 57049 1528411 175 3413 212 158 5092 1893 20 0 1 0 "
                + "12 172056576 3294 18446744073709551615 1 1 0 0 0 0 671173123 4096 1260 0 0 0 17 0 0 0\n";

        assertEquals(OptionalLong.of(987_654L), CgroupJobOwnership.parseStartTicks(tricky));
        assertEquals(OptionalLong.of(12L), CgroupJobOwnership.parseStartTicks(plain));
    }

    @Test
    void anUnreadableOrUnexpectedProcStatYieldsUnknownTicks(@TempDir Path proc) throws Exception {
        assertEquals(OptionalLong.empty(), CgroupJobOwnership.parseStartTicks(null));
        assertEquals(OptionalLong.empty(), CgroupJobOwnership.parseStartTicks(""));
        assertEquals(OptionalLong.empty(), CgroupJobOwnership.parseStartTicks("4242 no-parenthesis S 1 2 3"));
        assertEquals(OptionalLong.empty(), CgroupJobOwnership.parseStartTicks("4242 (truncated) S 1 2 3"));
        assertEquals(OptionalLong.empty(), CgroupJobOwnership.parseStartTicks(
                "4242 (x) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 not-a-number 1 2"));
        assertEquals(OptionalLong.empty(), CgroupJobOwnership.parseStartTicks(
                "4242 (x) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 0 1 2"), "zero ticks means unknown");

        assertEquals(OptionalLong.empty(), CgroupJobOwnership.startTicks(proc, 4_242L), "missing stat file");
        Files.createDirectories(proc.resolve("4242"));
        Files.writeString(proc.resolve("4242").resolve("stat"),
                "4242 (x y) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 555 1 2\n", StandardCharsets.US_ASCII);
        assertEquals(OptionalLong.of(555L), CgroupJobOwnership.startTicks(proc, 4_242L));
        Files.createDirectories(proc.resolve("99").resolve("stat"));
        assertEquals(OptionalLong.empty(), CgroupJobOwnership.startTicks(proc, 99L), "unreadable stat");
    }

    @Test
    void anUnreadableProcStatOfALiveOwnerLeavesItsCgroupIntact() {
        Verdict verdict = CgroupJobOwnership.decide(
                Optional.of(OTHER), SELF, pid -> Optional.of(new LiveProcess(pid, OptionalLong.empty())),
                MEMBERSHIP_MUST_NOT_BE_READ);

        assertEquals(Decision.LEAVE, verdict.decision(), "unknown ticks are never taken for a dead owner: "
                + verdict.reason());
    }

    @Test
    void aCgroupOwnedByAnotherLiveMinosProcessIsLeftIntact() {
        Verdict verdict = CgroupJobOwnership.decide(
                Optional.of(OTHER), SELF, alive(OTHER.pid(), OTHER.start()), MEMBERSHIP_MUST_NOT_BE_READ);

        assertEquals(Decision.LEAVE, verdict.decision(), "live owner must be left intact: " + verdict.reason());
    }

    /**
     * R2: a wall-clock step moves the start instant two JVMs derive for the same process, but not its
     * kernel start ticks. For a current mark the instant is not an input of the decision at all; for a
     * legacy wall-clock mark any instant difference of a live owner leaves the cgroup intact.
     */
    @Test
    void aWallClockStepNeverMakesALiveOwnerLookReused() {
        long oneHourMillis = 3_600_000L;
        for (long step : new long[] {-oneHourMillis, -2_001L, 2_001L, oneHourMillis}) {
            Mark legacy = Mark.legacyWallClock(OTHER.pid(), 1_700_000_100_000L + step, OTHER.token());

            Verdict verdict = CgroupJobOwnership.decide(
                    Optional.of(legacy), SELF, alive(OTHER.pid(), OTHER.start()), MEMBERSHIP_MUST_NOT_BE_READ);

            assertEquals(Decision.LEAVE, verdict.decision(), "step " + step + " ms: " + verdict.reason());
        }
        Verdict current = CgroupJobOwnership.decide(
                Optional.of(OTHER), SELF, alive(OTHER.pid(), OTHER.start()), MEMBERSHIP_MUST_NOT_BE_READ);
        assertEquals(Decision.LEAVE, current.decision(), current.reason());
    }

    @Test
    void aLegacyMarkWhoseOwnerPidIsGoneIsStillReclaimed() {
        Mark legacy = Mark.legacyWallClock(OTHER.pid(), 1_700_000_100_000L, OTHER.token());

        Verdict verdict = CgroupJobOwnership.decide(
                Optional.of(legacy), SELF, pid -> Optional.empty(), MEMBERSHIP_MUST_NOT_BE_READ);

        assertEquals(Decision.RECLAIM, verdict.decision(), verdict.reason());
    }

    @Test
    void aCgroupOwnedByThisJvmIsLeftIntactWithoutConsultingTheProcessTable() {
        OwnerLookup mustNotBeCalled = pid -> {
            throw new AssertionError("own cgroups are recognized by token, not by pid lookup");
        };

        Verdict verdict = CgroupJobOwnership.decide(
                Optional.of(SELF), SELF, mustNotBeCalled, MEMBERSHIP_MUST_NOT_BE_READ);

        assertEquals(Decision.LEAVE, verdict.decision(), verdict.reason());
    }

    @Test
    void aCgroupWhoseOwnerPidIsGoneIsReclaimed() {
        Verdict verdict = CgroupJobOwnership.decide(
                Optional.of(OTHER), SELF, pid -> Optional.empty(), MEMBERSHIP_MUST_NOT_BE_READ);

        assertEquals(Decision.RECLAIM, verdict.decision(), verdict.reason());
        assertTrue(verdict.reclaim());
    }

    /** A real PID reuse is always detected: the kernel start ticks of the new process differ, even by one. */
    @Test
    void aCgroupWhoseOwnerPidWasReusedByAnotherProcessIsReclaimed() {
        for (long reusedTicks : new long[] {OTHER.start() + 1L, OTHER.start() - 1L, OTHER.start() * 10L, 1L}) {
            Verdict verdict = CgroupJobOwnership.decide(
                    Optional.of(OTHER), SELF, alive(OTHER.pid(), reusedTicks), MEMBERSHIP_MUST_NOT_BE_READ);

            assertEquals(Decision.RECLAIM, verdict.decision(), "ticks " + reusedTicks + ": " + verdict.reason());
        }
    }

    @Test
    void anUnverifiableLiveOwnerIsNeverKilled() {
        Mark unknownStart = new Mark(OTHER.pid(), 0L, OTHER.token());

        Verdict markWithoutStart = CgroupJobOwnership.decide(
                Optional.of(unknownStart), SELF, alive(OTHER.pid(), 1L), MEMBERSHIP_MUST_NOT_BE_READ);
        Verdict processWithoutStart = CgroupJobOwnership.decide(
                Optional.of(OTHER), SELF, pid -> Optional.of(new LiveProcess(pid, OptionalLong.empty())),
                MEMBERSHIP_MUST_NOT_BE_READ);

        assertEquals(Decision.LEAVE, markWithoutStart.decision(), markWithoutStart.reason());
        assertEquals(Decision.LEAVE, processWithoutStart.decision(), processWithoutStart.reason());
    }

    @Test
    void anUnmarkedCgroupIsReclaimedOnlyWhenItHoldsNoProcess() {
        OwnerLookup mustNotBeCalled = pid -> {
            throw new AssertionError("an unmarked cgroup has no owner pid to look up");
        };

        Verdict empty = CgroupJobOwnership.decide(Optional.empty(), SELF, mustNotBeCalled, () -> 0L);
        Verdict populated = CgroupJobOwnership.decide(Optional.empty(), SELF, mustNotBeCalled, () -> 3L);

        assertEquals(Decision.RECLAIM, empty.decision(), empty.reason());
        assertEquals(Decision.LEAVE, populated.decision(), "unmarked cgroup with processes must be left: "
                + populated.reason());
        assertFalse(populated.reclaim());
    }

    @Test
    void aMembershipReadFailureOnAnUnmarkedCgroupPropagatesAsContainmentFailure() {
        IllegalStateException failure = new IllegalStateException("unable to read cgroup membership");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> CgroupJobOwnership.decide(
                Optional.empty(), SELF, pid -> Optional.empty(), () -> {
                    throw failure;
                }));

        assertEquals(failure, thrown);
    }

    private static OwnerLookup alive(long pid, long startTicks) {
        return candidate -> candidate == pid
                ? Optional.of(new LiveProcess(pid, OptionalLong.of(startTicks)))
                : Optional.empty();
    }
}
