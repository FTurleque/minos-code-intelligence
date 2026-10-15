package com.minos.io;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The acquisition budget of a lease, tested on its own (AUD-TST-02): it was only reached through the leases, so its
 * refusals (non-positive timeout, exhausted budget, interruption) had no direct proof.
 */
class LeaseDeadlineTest {

    @Test
    void aTimeoutThatIsMissingZeroOrNegativeIsRefused() {
        assertThrows(NullPointerException.class, () -> LeaseDeadline.after(null));
        assertEquals("lock timeout must be positive",
                assertThrows(IllegalArgumentException.class, () -> LeaseDeadline.after(Duration.ZERO)).getMessage());
        assertEquals("lock timeout must be positive",
                assertThrows(IllegalArgumentException.class, () -> LeaseDeadline.after(Duration.ofMillis(-1))).getMessage());
    }

    @Test
    void theRemainingBudgetNeverExceedsTheTimeoutAndShrinks() throws InterruptedException {
        Duration timeout = Duration.ofSeconds(30);
        LeaseDeadline deadline = LeaseDeadline.after(timeout);

        long first = deadline.remainingNanos();
        Thread.sleep(2);
        long second = deadline.remainingNanos();

        assertTrue(first <= timeout.toNanos(), "remaining " + first);
        assertTrue(second < first, first + " then " + second);
        assertTrue(second > 0L);
    }

    @Test
    void aTimeoutTooLargeForNanosecondsSaturatesInsteadOfOverflowing() {
        LeaseDeadline deadline = LeaseDeadline.after(Duration.ofSeconds(Long.MAX_VALUE));

        assertTrue(deadline.remainingNanos() > Duration.ofDays(365 * 100L).toNanos(), "no overflow to a past deadline");
    }

    @Test
    void anExhaustedBudgetFailsBeforeSleepingAndNamesTheResource() {
        LeaseDeadline deadline = LeaseDeadline.after(Duration.ofNanos(1));

        IOException failure = assertThrows(IOException.class, () -> deadline.pauseBeforeRetry("the project lease"));

        assertEquals("timed out waiting for the project lease after PT0.000000001S", failure.getMessage());
        assertEquals(0L, deadline.remainingNanos());
    }

    @Test
    void aBudgetThatRunsOutDuringThePauseFailsWithoutGrantingOneMoreAttempt() throws IOException {
        LeaseDeadline deadline = LeaseDeadline.after(Duration.ofMillis(120));
        IOException failure = null;
        int pauses = 0;
        // Each pause sleeps at most 50 ms: the budget is spent after a bounded number of them, and the last one fails
        // instead of returning with nothing left.
        while (failure == null && pauses < 100) {
            try {
                deadline.pauseBeforeRetry("the cache lock");
                pauses++;
                assertTrue(deadline.remainingNanos() > 0L, "a pause returned although the budget was spent");
            } catch (IOException timedOut) {
                failure = timedOut;
            }
        }

        assertTrue(failure != null, "the budget never ran out");
        assertEquals("timed out waiting for the cache lock after PT0.12S", failure.getMessage());
        assertTrue(pauses <= 3, pauses + " pauses within 120 ms, each of at most 50 ms");
    }

    @Test
    void anInterruptedPauseFailsAndKeepsTheInterruptFlag() {
        LeaseDeadline deadline = LeaseDeadline.after(Duration.ofSeconds(30));
        Thread.currentThread().interrupt();
        try {
            IOException failure = assertThrows(IOException.class, () -> deadline.pauseBeforeRetry("the run directory"));

            assertEquals("interrupted while waiting for the run directory", failure.getMessage());
            assertTrue(failure.getCause() instanceof InterruptedException);
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt must be restored for the caller");
        } finally {
            Thread.interrupted();
        }
        assertFalse(Thread.currentThread().isInterrupted());
    }
}
