package com.minos.runtime.local.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bornes refusées et règle de durée de vie de {@link RunDirectoryRetention}, sans dépendre de l'horloge. */
class RunDirectoryRetentionPolicyTest {

    private static final Duration WEEK = Duration.ofDays(7);
    private static final Duration DAY = Duration.ofDays(1);

    @Test
    void aBudgetThatIsNotPositiveIsRefusedWhicheverBoundItIs() {
        IllegalArgumentException scan = assertThrows(IllegalArgumentException.class,
                () -> new RunDirectoryRetention.Budgets(0L, 1L, 1L));
        assertThrows(IllegalArgumentException.class, () -> new RunDirectoryRetention.Budgets(1L, 0L, 1L));
        assertThrows(IllegalArgumentException.class, () -> new RunDirectoryRetention.Budgets(1L, 1L, 0L));
        assertThrows(IllegalArgumentException.class, () -> new RunDirectoryRetention.Budgets(1L, 1L, -5L));

        assertEquals("run retention budgets must be positive", scan.getMessage());
        assertEquals(1L, new RunDirectoryRetention.Budgets(1L, 2L, 3L).maxScanEntriesPerRun(),
                "the smallest positive budget is accepted");
    }

    @Test
    void aPolicyWithoutPositiveCountAndByteLimitsIsRefused() {
        IllegalArgumentException entries = assertThrows(IllegalArgumentException.class,
                () -> new RunDirectoryRetention.Policy(0, 1L, WEEK, DAY));
        IllegalArgumentException bytes = assertThrows(IllegalArgumentException.class,
                () -> new RunDirectoryRetention.Policy(1, 0L, WEEK, DAY));

        assertEquals("run retention limits must be positive", entries.getMessage());
        assertEquals("run retention limits must be positive", bytes.getMessage());
    }

    @Test
    void aPolicyWithoutAPositiveMaximumAgeIsRefused() {
        Duration negative = Duration.ofSeconds(-1);

        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                () -> new RunDirectoryRetention.Policy(1, 1L, Duration.ZERO, DAY));
        assertThrows(IllegalArgumentException.class, () -> new RunDirectoryRetention.Policy(1, 1L, negative, DAY));
        NullPointerException absent = assertThrows(NullPointerException.class,
                () -> new RunDirectoryRetention.Policy(1, 1L, null, DAY));

        assertEquals("run retention maxAge must be positive", zero.getMessage());
        assertEquals("maxAge", absent.getMessage());
    }

    @Test
    void aPolicyWithoutAPositiveResumeLifetimeIsRefused() {
        Duration negative = Duration.ofSeconds(-1);

        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                () -> new RunDirectoryRetention.Policy(1, 1L, WEEK, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new RunDirectoryRetention.Policy(1, 1L, WEEK, negative));
        NullPointerException absent = assertThrows(NullPointerException.class,
                () -> new RunDirectoryRetention.Policy(1, 1L, WEEK, null));

        assertEquals("run retention resumeTtl must be positive", zero.getMessage());
        assertEquals("resumeTtl", absent.getMessage());
    }

    @Test
    void aMarkerNeverShortensALifetimeAndLengthensItOnlyUpToTheResumeLifetime() {
        RunDirectoryRetention.Policy shortResume = new RunDirectoryRetention.Policy(1, 1L, WEEK, DAY);
        RunDirectoryRetention.Policy longResume = new RunDirectoryRetention.Policy(1, 1L, DAY, WEEK);
        RunDirectoryRetention.Policy equalResume = new RunDirectoryRetention.Policy(1, 1L, DAY, DAY);

        assertEquals(WEEK, shortResume.lifetime(true), "holding a run never shortens its life");
        assertEquals(WEEK, shortResume.lifetime(false));
        assertEquals(WEEK, longResume.lifetime(true), "a held run lives the resume lifetime when it is longer");
        assertEquals(DAY, longResume.lifetime(false), "an ordinary run ignores the resume lifetime");
        assertEquals(DAY, equalResume.lifetime(true));
    }

    @Test
    void thePolicyWithoutAnExplicitResumeLifetimeUsesTheDefaultOne() {
        RunDirectoryRetention.Policy policy = new RunDirectoryRetention.Policy(3, 4L, WEEK);

        assertEquals(RunDirectoryRetention.DEFAULT_RESUME_TTL, policy.resumeTtl());
        assertEquals(3, policy.maxEntries());
        assertEquals(4L, policy.maxBytes());
    }

    @Test
    void aProtectedRunOutsideTheRunsRootIsRefusedAndNothingIsReclaimed(@TempDir Path home) throws Exception {
        Path runs = home.resolve("runs");
        Path expired = Files.createDirectories(runs.resolve("expired"));
        Files.writeString(expired.resolve("index.scip"), "artifact");
        Path outside = Files.createDirectories(home.resolve("outside-run"));
        Instant farFuture = Instant.parse("2100-01-01T00:00:00Z");
        RunDirectoryRetention.Policy policy = new RunDirectoryRetention.Policy(1, 1L, DAY, DAY);

        IOException refusal = assertThrows(IOException.class,
                () -> RunDirectoryRetention.prune(runs, outside, policy, farFuture));

        assertEquals("protected run root escapes MINOS runs root", refusal.getMessage());
        assertTrue(Files.exists(expired.resolve("index.scip")),
                "a refused prune must not have reclaimed anything, however expired");
        assertTrue(Files.isDirectory(outside));
    }

    @Test
    void theDefaultPruneCreatesTheRunsRootAndKeepsARunThatWasJustWritten(@TempDir Path home) throws Exception {
        Path runs = home.resolve("runs");
        Path current = runs.resolve("current");

        RunDirectoryRetention.prune(runs, current);
        assertTrue(Files.isDirectory(runs), "retention prepares its own root");

        Path recent = Files.createDirectories(runs.resolve("recent"));
        Files.writeString(recent.resolve("index.scip"), "artifact");
        RunDirectoryRetention.prune(runs, null);

        assertEquals("artifact", Files.readString(recent.resolve("index.scip")),
                "a run within every default bound is retained");
    }
}
