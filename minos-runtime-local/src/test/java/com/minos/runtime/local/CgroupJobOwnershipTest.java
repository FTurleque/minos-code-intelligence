package com.minos.runtime.local;

import com.minos.runtime.local.CgroupJobOwnership.Decision;
import com.minos.runtime.local.CgroupJobOwnership.Mark;
import com.minos.runtime.local.CgroupJobOwnership.Namespaces;
import com.minos.runtime.local.CgroupJobOwnership.OwnerLookup;
import com.minos.runtime.local.CgroupJobOwnership.OwnerStatus;
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

import static com.minos.runtime.local.CgroupSweepFixtures.NAMESPACES;
import static com.minos.runtime.local.CgroupSweepFixtures.OTHER_PID_NAMESPACE;
import static com.minos.runtime.local.CgroupSweepFixtures.OTHER_TIME_NAMESPACE;
import static com.minos.runtime.local.CgroupSweepFixtures.OWNER;
import static com.minos.runtime.local.CgroupSweepFixtures.SELF;
import static com.minos.runtime.local.CgroupSweepFixtures.nobodyIsAlive;
import static com.minos.runtime.local.CgroupSweepFixtures.onlyAlive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Host-independent proof of the stale-cgroup decision. The default conclusion is to leave a cgroup
 * intact: it is reclaimed only with a positive proof that its owner is dead (a process table proven
 * readable that has no such PID, or complete and different kernel start ticks for a PID that exists),
 * in the same PID and time namespaces as the sweeper, whatever the wall clock did. An empty cgroup is
 * removed, never killed.
 */
class CgroupJobOwnershipTest {

    private static final long MAX_PID_VALUE = 9_999_999_999L;
    /** The mark-name pattern of the previous (S3) MINOS release, verbatim: an older MINOS still parses with it. */
    private static final Pattern PREVIOUS_RELEASE_MARKED_NAME = Pattern.compile(
            "^(?<job>[A-Za-z0-9][A-Za-z0-9._-]*)\\.own-(?<pid>[0-9]{1,10})-(?<start>[0-9]{1,19})-(?<token>[0-9a-f]{8})$");
    /** The mark-name pattern of the build that wrote ticks without namespaces, verbatim (an unreleased develop build). */
    private static final Pattern UNSTAMPED_TICKS_MARKED_NAME = Pattern.compile(
            "^(?<job>[A-Za-z0-9][A-Za-z0-9._-]*)\\.own-(?<pid>[0-9]{1,10})-"
                    + "(?:t(?<ticks>[0-9]{1,19})|(?<legacy>[0-9]{1,19}))-(?<token>[0-9a-f]{8})$");
    private static final LongSupplier MEMBERSHIP_MUST_NOT_BE_READ = () -> {
        throw new AssertionError("the decision must not read cgroup membership for a marked cgroup");
    };
    private static final OwnerLookup TABLE_MUST_NOT_BE_READ = pid -> {
        throw new AssertionError("the process table must not be consulted: " + pid);
    };

    @Test
    void theMarkRoundTripsThroughTheDirectoryName() {
        String name = OWNER.markedName("minos-provider-2b1f0c5e-1d2c-4a4b-9a2e-1234567890ab");

        assertEquals("minos-provider-2b1f0c5e-1d2c-4a4b-9a2e-1234567890ab.own-7777-t2345678"
                + "-n4026531836_4026531834-deadbeef", name);
        assertEquals(Optional.of(OWNER), Mark.parse(name));
        assertEquals(StartClock.BOOT_TICKS, Mark.parse(name).orElseThrow().clock());
        assertEquals(NAMESPACES, Mark.parse(name).orElseThrow().namespaces());
    }

    @Test
    void aMarkWithoutNamespacesStillRoundTripsAndStaysUnknown() {
        Mark unstamped = new Mark(7_777L, 2_345_678L, "deadbeef");

        String name = unstamped.markedName("minos-provider-x");

        assertEquals("minos-provider-x.own-7777-t2345678-deadbeef", name);
        assertEquals(Optional.of(unstamped), Mark.parse(name));
        assertEquals(Namespaces.UNKNOWN, Mark.parse(name).orElseThrow().namespaces());
        assertFalse(Namespaces.UNKNOWN.known());
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
     * which its decision (identical to the current one for unmarked names) leaves intact. The same holds
     * for the build that wrote ticks without namespaces: it reads a stamped mark as an unmarked name.
     */
    @Test
    void anOlderMinosCannotMistakeTheCurrentMarkForItsOwnFormat() {
        String current = CgroupJobOwnership.CURRENT.markedName("minos-provider-new");
        String widest = new Mark(MAX_PID_VALUE, Long.MAX_VALUE, "ffffffff")
                .withNamespaces(new Namespaces(Long.MAX_VALUE, Long.MAX_VALUE)).markedName("minos-x");
        String stamped = OWNER.markedName("minos-provider-new");

        for (String name : new String[] {widest, stamped}) {
            assertFalse(PREVIOUS_RELEASE_MARKED_NAME.matcher(name).matches(), name);
            assertFalse(UNSTAMPED_TICKS_MARKED_NAME.matcher(name).matches(), name);
        }
        assertFalse(PREVIOUS_RELEASE_MARKED_NAME.matcher(current).matches(), current);
        assertTrue(PREVIOUS_RELEASE_MARKED_NAME.matcher(
                Mark.legacyWallClock(7_777L, 1_700_000_100_000L, "deadbeef").markedName("minos-x")).matches(),
                "the pattern must be the real previous one: it recognizes its own marks");
        assertTrue(UNSTAMPED_TICKS_MARKED_NAME.matcher(
                new Mark(7_777L, 2_345_678L, "deadbeef").markedName("minos-x")).matches(),
                "the pattern must be the real one of the unstamped build: it recognizes its own marks");

        OwnerLookup mustNotBeCalled = pid -> {
            throw new AssertionError("an unmarked cgroup has no owner pid to look up");
        };
        Verdict olderVerdict = CgroupJobOwnership.decide(Optional.empty(), SELF, mustNotBeCalled, () -> 1L);
        assertEquals(Decision.LEAVE, olderVerdict.decision(), olderVerdict.reason());
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
        assertTrue(Mark.parse("minos-x.own-7777-t1-n0_5-deadbeef").isEmpty(), "a stamped PID namespace is positive");
        assertTrue(Mark.parse("minos-x.own-7777-t1-n12-deadbeef").isEmpty(), "the time namespace is mandatory");
        assertTrue(Mark.parse("minos-x.own-7777-t1-n12_-deadbeef").isEmpty());
        assertTrue(Mark.parse("minos-x.own-7777-t1-n_5-deadbeef").isEmpty());
        assertTrue(Mark.parse("minos-x.own-7777-t1-n99999999999999999999_5-deadbeef").isEmpty(), "inode overflow");
        assertTrue(Mark.parse("minos-x.own-7777-1700000100000-n12_5-deadbeef").isEmpty(),
                "a legacy wall-clock mark never carries namespaces");
    }

    @Test
    void theLongestSafeJobNameStillFitsWithItsMark() throws Exception {
        String longest = "minos-" + "x".repeat(90);
        assertEquals(96, longest.length());

        String marked = LinuxCgroupJob.markedJobName(longest);

        assertEquals(Optional.of(CgroupJobOwnership.CURRENT), Mark.parse(marked));
        assertEquals(86, CgroupJobOwnership.MAX_SUFFIX_LENGTH,
                "separator 5 + pid 10 + 1 + t 1 + ticks 19 + -n 2 + pid ns 19 + _ 1 + time ns 19 + 1 + token 8");
        assertTrue(marked.length() <= 96 + CgroupJobOwnership.MAX_SUFFIX_LENGTH, marked);
        assertTrue(CgroupJobOwnership.CURRENT.suffix().length() <= CgroupJobOwnership.MAX_SUFFIX_LENGTH);
        Mark widest = new Mark(MAX_PID_VALUE, Long.MAX_VALUE, "ffffffff")
                .withNamespaces(new Namespaces(Long.MAX_VALUE, Long.MAX_VALUE));
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
        assertEquals(Namespaces.read(CgroupJobOwnership.PROC, ProcessHandle.current().pid()),
                CgroupJobOwnership.CURRENT.namespaces());
        assertEquals(Optional.of(CgroupJobOwnership.CURRENT),
                Mark.parse(CgroupJobOwnership.CURRENT.markedName("minos-probe-1")));
        assertThrows(IllegalArgumentException.class, () -> new Mark(1L, 0L, "not-hex!"));
        assertThrows(IllegalArgumentException.class, () -> new Mark(1L, -1L, "deadbeef"));
        assertThrows(IllegalArgumentException.class, () -> new Namespaces(-1L, 0L));
    }

    @Test
    void aNamespaceLinkTargetYieldsItsInodeAndNothingElseDoes() {
        assertEquals(OptionalLong.of(4_026_531_836L), Namespaces.parseInode("pid:[4026531836]"));
        assertEquals(OptionalLong.of(4_026_531_834L), Namespaces.parseInode("time:[4026531834]"));
        assertEquals(OptionalLong.empty(), Namespaces.parseInode(null));
        assertEquals(OptionalLong.empty(), Namespaces.parseInode(""));
        assertEquals(OptionalLong.empty(), Namespaces.parseInode("pid:[]"));
        assertEquals(OptionalLong.empty(), Namespaces.parseInode("pid:[0]"));
        assertEquals(OptionalLong.empty(), Namespaces.parseInode("pid:[12x]"));
        assertEquals(OptionalLong.empty(), Namespaces.parseInode("anon_inode:[99999999999999999999]"));
        assertEquals(OptionalLong.empty(), Namespaces.parseInode("/proc/1/ns/pid"));
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
                "4242 (x) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 not-a-number 1 2\n"));
        assertEquals(OptionalLong.empty(), CgroupJobOwnership.parseStartTicks(
                "4242 (x) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 0 1 2\n"), "zero ticks means unknown");

        assertEquals(OptionalLong.empty(), CgroupJobOwnership.startTicks(proc, 4_242L), "missing stat file");
        Files.createDirectories(proc.resolve("4242"));
        Files.writeString(proc.resolve("4242").resolve("stat"),
                "4242 (x y) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 555 1 2\n", StandardCharsets.US_ASCII);
        assertEquals(OptionalLong.of(555L), CgroupJobOwnership.startTicks(proc, 4_242L));
        Files.createDirectories(proc.resolve("99").resolve("stat"));
        assertEquals(OptionalLong.empty(), CgroupJobOwnership.startTicks(proc, 99L), "unreadable stat");
    }

    /**
     * A read cut inside field 22 gives a shorter number that is still a number; taken at face value it
     * would make a live owner look like a PID reuse. Field 22 is trusted only when the record is complete:
     * a field follows it and the line ends with its newline.
     */
    @Test
    void aTruncatedProcStatNeverYieldsStartTicks() {
        String head = "4242 (x) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 ";

        assertEquals(OptionalLong.empty(), CgroupJobOwnership.parseStartTicks(head + "987"),
                "cut inside field 22: the value is a prefix of the real one");
        assertEquals(OptionalLong.empty(), CgroupJobOwnership.parseStartTicks(head + "987 "),
                "cut right after field 22: nothing proves the number is complete");
        assertEquals(OptionalLong.empty(), CgroupJobOwnership.parseStartTicks(head + "987654 12"),
                "cut inside field 23: the record has no newline");
        assertEquals(OptionalLong.empty(), CgroupJobOwnership.parseStartTicks(head + "987654\n"),
                "field 22 is the last field: a complete record has more");
        assertEquals(OptionalLong.of(987_654L), CgroupJobOwnership.parseStartTicks(head + "987654 1 2\n"));
    }

    @Test
    void aTruncatedProcStatOfALiveOwnerLeavesItsCgroupIntact() {
        // The owner is alive; the read returned only the first digits of field 22 (987 of 987654).
        OwnerLookup truncated = pid -> OwnerStatus.present(CgroupJobOwnership.parseStartTicks(
                "7777 (java) S 1 7777 7777 0 -1 4194560 100 0 0 0 1 1 0 0 20 0 1 0 987"));

        Verdict verdict = CgroupJobOwnership.decide(Optional.of(OWNER), SELF, truncated, MEMBERSHIP_MUST_NOT_BE_READ);

        assertEquals(Decision.LEAVE, verdict.decision(), "a partial read is never a pid reuse: " + verdict.reason());
    }

    @Test
    void anUnreadableProcStatOfALiveOwnerLeavesItsCgroupIntact() {
        Verdict verdict = CgroupJobOwnership.decide(
                Optional.of(OWNER), SELF, pid -> OwnerStatus.present(OptionalLong.empty()),
                MEMBERSHIP_MUST_NOT_BE_READ);

        assertEquals(Decision.LEAVE, verdict.decision(), "unknown ticks are never taken for a dead owner: "
                + verdict.reason());
    }

    @Test
    void aCgroupOwnedByAnotherLiveMinosProcessIsLeftIntact() {
        Verdict verdict = CgroupJobOwnership.decide(
                Optional.of(OWNER), SELF, onlyAlive(OWNER.pid(), OWNER.start()), MEMBERSHIP_MUST_NOT_BE_READ);

        assertEquals(Decision.LEAVE, verdict.decision(), "live owner must be left intact: " + verdict.reason());
    }

    /**
     * R2: a wall-clock step moves the start instant two JVMs derive for the same process, but not its
     * kernel start ticks. For a current mark the instant is not an input of the decision at all; a
     * legacy wall-clock mark is never reclaimed, whatever instant it carries.
     */
    @Test
    void aWallClockStepNeverMakesALiveOwnerLookReused() {
        long oneHourMillis = 3_600_000L;
        for (long step : new long[] {-oneHourMillis, -2_001L, 2_001L, oneHourMillis}) {
            Mark legacy = Mark.legacyWallClock(OWNER.pid(), 1_700_000_100_000L + step, OWNER.token());

            Verdict verdict = CgroupJobOwnership.decide(
                    Optional.of(legacy), SELF, onlyAlive(OWNER.pid(), OWNER.start()), MEMBERSHIP_MUST_NOT_BE_READ);

            assertEquals(Decision.LEAVE, verdict.decision(), "step " + step + " ms: " + verdict.reason());
        }
        Verdict current = CgroupJobOwnership.decide(
                Optional.of(OWNER), SELF, onlyAlive(OWNER.pid(), OWNER.start()), MEMBERSHIP_MUST_NOT_BE_READ);
        assertEquals(Decision.LEAVE, current.decision(), current.reason());
    }

    /**
     * A mark from before the ticks existed carries a wall-clock instant and no namespaces: nothing in it
     * proves the owner is dead, not even a PID that looks absent, so it is never reclaimed.
     */
    @Test
    void aLegacyMarkIsNeverReclaimedEvenWhenItsOwnerPidLooksGone() {
        Mark legacy = Mark.legacyWallClock(OWNER.pid(), 1_700_000_100_000L, OWNER.token());

        Verdict verdict = CgroupJobOwnership.decide(
                Optional.of(legacy), SELF, nobodyIsAlive(), MEMBERSHIP_MUST_NOT_BE_READ);

        assertEquals(Decision.LEAVE, verdict.decision(), verdict.reason());
    }

    @Test
    void aCgroupOwnedByThisJvmIsLeftIntactWithoutConsultingTheProcessTable() {
        Verdict verdict = CgroupJobOwnership.decide(
                Optional.of(SELF), SELF, TABLE_MUST_NOT_BE_READ, MEMBERSHIP_MUST_NOT_BE_READ);

        assertEquals(Decision.LEAVE, verdict.decision(), verdict.reason());
    }

    @Test
    void aCgroupWhoseOwnerPidIsGoneIsReclaimed() {
        Verdict verdict = CgroupJobOwnership.decide(
                Optional.of(OWNER), SELF, nobodyIsAlive(), MEMBERSHIP_MUST_NOT_BE_READ);

        assertEquals(Decision.RECLAIM, verdict.decision(), verdict.reason());
        assertTrue(verdict.reclaim());
        assertTrue(verdict.kills(), "a proven-dead owner's cgroup is killed before it is removed");
    }

    /** A real PID reuse is always detected: the kernel start ticks of the new process differ, even by one. */
    @Test
    void aCgroupWhoseOwnerPidWasReusedByAnotherProcessIsReclaimed() {
        for (long reusedTicks : new long[] {OWNER.start() + 1L, OWNER.start() - 1L, OWNER.start() * 10L, 1L}) {
            Verdict verdict = CgroupJobOwnership.decide(
                    Optional.of(OWNER), SELF, onlyAlive(OWNER.pid(), reusedTicks), MEMBERSHIP_MUST_NOT_BE_READ);

            assertEquals(Decision.RECLAIM, verdict.decision(), "ticks " + reusedTicks + ": " + verdict.reason());
        }
    }

    @Test
    void anUnverifiableLiveOwnerIsNeverKilled() {
        Mark unknownStart = new Mark(OWNER.pid(), 0L, OWNER.token()).withNamespaces(NAMESPACES);

        Verdict markWithoutStart = CgroupJobOwnership.decide(
                Optional.of(unknownStart), SELF, onlyAlive(OWNER.pid(), 1L), MEMBERSHIP_MUST_NOT_BE_READ);
        Verdict processWithoutStart = CgroupJobOwnership.decide(
                Optional.of(OWNER), SELF, pid -> OwnerStatus.present(OptionalLong.empty()),
                MEMBERSHIP_MUST_NOT_BE_READ);

        assertEquals(Decision.LEAVE, markWithoutStart.decision(), markWithoutStart.reason());
        assertEquals(Decision.LEAVE, processWithoutStart.decision(), processWithoutStart.reason());
    }

    /**
     * "No such process" is only a proof of death when the process table itself could be trusted. When it
     * cannot answer (unreadable, partial, hiding other accounts) the owner may be alive: leave the cgroup.
     */
    @Test
    void anOwnerTheProcessTableCannotVerifyIsNeverReclaimed() {
        Verdict verdict = CgroupJobOwnership.decide(Optional.of(OWNER), SELF,
                CgroupSweepFixtures.unreadable("the process table cannot be read"), MEMBERSHIP_MUST_NOT_BE_READ);

        assertEquals(Decision.LEAVE, verdict.decision(), verdict.reason());
        assertFalse(verdict.reclaim());
        assertTrue(verdict.reason().contains("the process table cannot be read"), verdict.reason());
    }

    @Test
    void aMarkOfAnotherPidNamespaceIsNeverReclaimed() {
        Mark foreign = OWNER.withNamespaces(OTHER_PID_NAMESPACE);

        Verdict absent = CgroupJobOwnership.decide(
                Optional.of(foreign), SELF, nobodyIsAlive(), MEMBERSHIP_MUST_NOT_BE_READ);
        Verdict otherProcessAtThatPid = CgroupJobOwnership.decide(
                Optional.of(foreign), SELF, onlyAlive(foreign.pid(), foreign.start() + 1L), MEMBERSHIP_MUST_NOT_BE_READ);

        assertEquals(Decision.LEAVE, absent.decision(), "a pid of another namespace looks absent here: " + absent.reason());
        assertEquals(Decision.LEAVE, otherProcessAtThatPid.decision(),
                "a pid of another namespace designates another process here: " + otherProcessAtThatPid.reason());
    }

    @Test
    void aMarkOfAnotherTimeNamespaceIsNeverReclaimed() {
        Mark foreign = OWNER.withNamespaces(OTHER_TIME_NAMESPACE);

        Verdict verdict = CgroupJobOwnership.decide(
                Optional.of(foreign), SELF, onlyAlive(foreign.pid(), foreign.start() + 10_000_000L),
                MEMBERSHIP_MUST_NOT_BE_READ);

        assertEquals(Decision.LEAVE, verdict.decision(),
                "start ticks read in another time namespace are not comparable: " + verdict.reason());
    }

    @Test
    void aMarkWithoutNamespaceStampIsNeverReclaimed() {
        Mark unstamped = new Mark(OWNER.pid(), OWNER.start(), OWNER.token());

        Verdict verdict = CgroupJobOwnership.decide(
                Optional.of(unstamped), SELF, nobodyIsAlive(), MEMBERSHIP_MUST_NOT_BE_READ);

        assertEquals(Decision.LEAVE, verdict.decision(), verdict.reason());
    }

    @Test
    void aSweeperWhoseOwnNamespacesCannotBeReadReclaimsNothing() {
        Mark blind = new Mark(SELF.pid(), SELF.start(), SELF.token());

        Verdict verdict = CgroupJobOwnership.decide(
                Optional.of(OWNER), blind, nobodyIsAlive(), MEMBERSHIP_MUST_NOT_BE_READ);

        assertEquals(Decision.LEAVE, verdict.decision(), verdict.reason());
    }

    @Test
    void anUnmarkedCgroupIsRemovedWhenEmptyAndLeftWhenItHoldsAnyProcess() {
        Verdict empty = CgroupJobOwnership.decide(Optional.empty(), SELF, TABLE_MUST_NOT_BE_READ, () -> 0L);
        Verdict populated = CgroupJobOwnership.decide(Optional.empty(), SELF, TABLE_MUST_NOT_BE_READ, () -> 3L);

        assertEquals(Decision.REMOVE_EMPTY, empty.decision(), empty.reason());
        assertTrue(empty.reclaim());
        assertEquals(Decision.LEAVE, populated.decision(), "unmarked cgroup with processes must be left: "
                + populated.reason());
        assertFalse(populated.reclaim());
    }

    /**
     * The membership read that found the cgroup empty is a moment, not a fact: an older MINOS can start a
     * job in it right after. Nothing proves that job is dead, so an empty cgroup is removed and never killed.
     */
    @Test
    void anEmptyUnmarkedCgroupIsNeverKilled() {
        Verdict empty = CgroupJobOwnership.decide(Optional.empty(), SELF, TABLE_MUST_NOT_BE_READ, () -> 0L);

        assertFalse(empty.kills(), "an empty cgroup is removed, never killed: " + empty.reason());
    }

    @Test
    void aMembershipReadFailureOnAnUnmarkedCgroupPropagatesAsContainmentFailure() {
        IllegalStateException failure = new IllegalStateException("unable to read cgroup membership");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> CgroupJobOwnership.decide(
                Optional.empty(), SELF, TABLE_MUST_NOT_BE_READ, () -> {
                    throw failure;
                }));

        assertEquals(failure, thrown);
    }
}
