package com.minos.runtime;

import com.minos.runtime.CgroupJobOwnership.Decision;
import com.minos.runtime.CgroupJobOwnership.LiveProcess;
import com.minos.runtime.CgroupJobOwnership.Mark;
import com.minos.runtime.CgroupJobOwnership.OwnerLookup;
import com.minos.runtime.CgroupJobOwnership.Verdict;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Host-independent proof of the stale-cgroup decision: only a cgroup whose owning MINOS process is
 * dead (PID gone or reused) is reclaimed; the jobs of another live MINOS instance are left intact.
 */
class CgroupJobOwnershipTest {

    private static final long MAX_PID_VALUE = 9_999_999_999L;
    private static final Mark SELF = new Mark(4_242L, 1_700_000_000_000L, "0badcafe");
    private static final Mark OTHER = new Mark(7_777L, 1_700_000_100_000L, "deadbeef");
    private static final LongSupplier MEMBERSHIP_MUST_NOT_BE_READ = () -> {
        throw new AssertionError("the decision must not read cgroup membership for a marked cgroup");
    };

    @Test
    void theMarkRoundTripsThroughTheDirectoryName() {
        String name = OTHER.markedName("minos-provider-2b1f0c5e-1d2c-4a4b-9a2e-1234567890ab");

        assertEquals("minos-provider-2b1f0c5e-1d2c-4a4b-9a2e-1234567890ab.own-7777-1700000100000-deadbeef", name);
        assertEquals(Optional.of(OTHER), Mark.parse(name));
    }

    @Test
    void legacyAndMalformedNamesCarryNoMark() {
        assertTrue(Mark.parse("minos-provider-2b1f0c5e").isEmpty(), "legacy name without mark");
        assertTrue(Mark.parse("minos-controller").isEmpty());
        assertTrue(Mark.parse("minos-x.own-7777-1700000100000-DEADBEEF").isEmpty(), "token must be lowercase hex");
        assertTrue(Mark.parse("minos-x.own-0-1700000100000-deadbeef").isEmpty(), "pid must be positive");
        assertTrue(Mark.parse("minos-x.own-7777-1700000100000-deadbeef/evil").isEmpty());
        assertTrue(Mark.parse(".own-7777-1700000100000-deadbeef").isEmpty(), "job part is mandatory");
    }

    @Test
    void theLongestSafeJobNameStillFitsWithItsMark() throws Exception {
        String longest = "minos-" + "x".repeat(90);
        assertEquals(96, longest.length());

        String marked = LinuxCgroupJob.markedJobName(longest);

        assertEquals(Optional.of(CgroupJobOwnership.CURRENT), Mark.parse(marked));
        assertTrue(marked.length() <= 96 + CgroupJobOwnership.MAX_SUFFIX_LENGTH, marked);
        assertTrue(CgroupJobOwnership.CURRENT.suffix().length() <= CgroupJobOwnership.MAX_SUFFIX_LENGTH);
        assertTrue(new Mark(MAX_PID_VALUE, Long.MAX_VALUE, "ffffffff").suffix().length()
                <= CgroupJobOwnership.MAX_SUFFIX_LENGTH, "the bound must hold for the widest mark");
        assertThrows(IOException.class, () -> LinuxCgroupJob.markedJobName(longest + "x"));
        assertThrows(IOException.class, () -> LinuxCgroupJob.markedJobName("../escape"));
    }

    @Test
    void theCurrentMarkDescribesTheRunningJvm() {
        assertEquals(ProcessHandle.current().pid(), CgroupJobOwnership.CURRENT.pid());
        assertEquals(Optional.of(CgroupJobOwnership.CURRENT),
                Mark.parse(CgroupJobOwnership.CURRENT.markedName("minos-probe-1")));
        assertThrows(IllegalArgumentException.class, () -> new Mark(1L, 0L, "not-hex!"));
    }

    @Test
    void aCgroupOwnedByAnotherLiveMinosProcessIsLeftIntact() {
        Verdict verdict = CgroupJobOwnership.decide(
                Optional.of(OTHER), SELF, alive(OTHER.pid(), OTHER.startEpochMillis()), MEMBERSHIP_MUST_NOT_BE_READ);

        assertEquals(Decision.LEAVE, verdict.decision(), "live owner must be left intact: " + verdict.reason());
    }

    @Test
    void aCgroupOwnedByAnotherLiveMinosProcessToleratesStartInstantRounding() {
        Verdict verdict = CgroupJobOwnership.decide(
                Optional.of(OTHER), SELF,
                alive(OTHER.pid(), OTHER.startEpochMillis() + CgroupJobOwnership.START_INSTANT_TOLERANCE_MILLIS),
                MEMBERSHIP_MUST_NOT_BE_READ);

        assertEquals(Decision.LEAVE, verdict.decision(), verdict.reason());
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

    @Test
    void aCgroupWhoseOwnerPidWasReusedByAnotherProcessIsReclaimed() {
        long reusedStart = OTHER.startEpochMillis() + CgroupJobOwnership.START_INSTANT_TOLERANCE_MILLIS + 1L;

        Verdict verdict = CgroupJobOwnership.decide(
                Optional.of(OTHER), SELF, alive(OTHER.pid(), reusedStart), MEMBERSHIP_MUST_NOT_BE_READ);

        assertEquals(Decision.RECLAIM, verdict.decision(), verdict.reason());
    }

    @Test
    void anUnverifiableLiveOwnerIsNeverKilled() {
        Mark unknownStart = new Mark(OTHER.pid(), 0L, OTHER.token());

        Verdict markWithoutStart = CgroupJobOwnership.decide(
                Optional.of(unknownStart), SELF, alive(OTHER.pid(), 1L), MEMBERSHIP_MUST_NOT_BE_READ);
        Verdict processWithoutStart = CgroupJobOwnership.decide(
                Optional.of(OTHER), SELF, pid -> Optional.of(new LiveProcess(pid, Optional.empty())),
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

    private static OwnerLookup alive(long pid, long startEpochMillis) {
        return candidate -> candidate == pid
                ? Optional.of(new LiveProcess(pid, Optional.of(Instant.ofEpochMilli(startEpochMillis))))
                : Optional.empty();
    }
}
