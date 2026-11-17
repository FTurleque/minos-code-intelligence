package com.minos.runtime.local.process;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ownership tracker enumerates the descendants of a live provider often at first, then rarely (AUD-PERF-08):
 * once the initial phase is over, a window of at least two measured seconds sees at least one enumeration, so a
 * stopped tracker cannot pass for a saving, and at most 15 per measured second. The plugin carries the same rule
 * and the same test.
 *
 * <p>The window opens after a first observed enumeration and the initial phase, and its duration is the one
 * read on the monotonic clock, never the duration the test asked to sleep.</p>
 */
class ProcessOwnershipTrackerCadenceTest {

    /** The initial phase is one second from the tracker's start; the margin keeps the window clear of its end. */
    private static final long AFTER_FIRST_ENUMERATION_MILLIS = 1_300L;
    private static final long MINIMUM_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(2L);
    private static final long FIRST_ENUMERATION_DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(10L);
    private static final double MAXIMUM_ENUMERATIONS_PER_SECOND = 15.0;

    @Test
    void aLiveProcessIsEnumeratedRarelyOnceTheInitialPhaseIsOver() throws Exception {
        CountingProcess root = new CountingProcess();
        int inWindow;
        long windowNanos;
        try (ProcessOwnershipTracker ignored = new ProcessOwnershipTracker(root)) {
            awaitFirstEnumeration(root);
            Thread.sleep(AFTER_FIRST_ENUMERATION_MILLIS);
            // Clock then counter on opening, counter then clock on closing: the counted enumerations all fall
            // inside the measured window, so the measured duration is never shorter than the counted one.
            long openedAt = System.nanoTime();
            int before = root.enumerations.get();
            long closingAt = openedAt + MINIMUM_WINDOW_NANOS;
            for (long now = System.nanoTime(); now < closingAt; now = System.nanoTime()) {
                TimeUnit.NANOSECONDS.sleep(closingAt - now);
            }
            inWindow = root.enumerations.get() - before;
            windowNanos = System.nanoTime() - openedAt;
        }

        assertCadence(inWindow, windowNanos);
    }

    @Test
    void aWindowThatLastedLongerThanPlannedIsJudgedOnItsMeasuredDuration() {
        assertDoesNotThrow(() -> assertCadence(45, TimeUnit.MILLISECONDS.toNanos(3_500L)));
    }

    @Test
    void aCadenceAboveTheBoundIsRefusedWithItsMeasures() {
        AssertionError error = assertThrows(AssertionError.class,
                () -> assertCadence(40, TimeUnit.SECONDS.toNanos(2L)));

        assertTrue(error.getMessage().contains("40 enumerations"), error.getMessage());
        assertTrue(error.getMessage().contains("2000 ms"), error.getMessage());
        assertTrue(error.getMessage().contains("at most 30"), error.getMessage());
    }

    @Test
    void aTrackerThatStoppedEnumeratingIsRefused() {
        assertThrows(AssertionError.class, () -> assertCadence(0, TimeUnit.SECONDS.toNanos(2L)));
    }

    /** At least one enumeration, at most 15 per measured second, rounded down. */
    static void assertCadence(int enumerations, long measuredNanos) {
        long measuredMillis = TimeUnit.NANOSECONDS.toMillis(measuredNanos);
        long maximum = (long) Math.floor(MAXIMUM_ENUMERATIONS_PER_SECOND * measuredNanos / 1_000_000_000.0);
        if (enumerations < 1) {
            throw new AssertionError("a stopped tracker is not a saving: " + enumerations
                    + " enumerations in " + measuredMillis + " ms");
        }
        if (enumerations > maximum) {
            throw new AssertionError("expected at most " + maximum + " enumerations in " + measuredMillis
                    + " ms (" + MAXIMUM_ENUMERATIONS_PER_SECOND + " per second), got " + enumerations
                    + " enumerations");
        }
    }

    private static void awaitFirstEnumeration(CountingProcess root) throws InterruptedException {
        long deadline = System.nanoTime() + FIRST_ENUMERATION_DEADLINE_NANOS;
        while (root.enumerations.get() == 0) {
            if (System.nanoTime() - deadline > 0L) {
                throw new AssertionError("the tracker never enumerated the descendants of a live process");
            }
            Thread.sleep(5L);
        }
    }

    /** A live process whose descendants are counted and always empty; it dies when asked to. */
    private static final class CountingProcess extends Process {
        private final AtomicInteger enumerations = new AtomicInteger();
        private final AtomicBoolean alive = new AtomicBoolean(true);

        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public boolean isAlive() { return alive.get(); }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return !alive.get();
        }

        @Override
        public int exitValue() {
            if (alive.get()) throw new IllegalThreadStateException("still running");
            return 0;
        }

        @Override
        public void destroy() {
            alive.set(false);
        }

        @Override
        public Process destroyForcibly() {
            alive.set(false);
            return this;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            enumerations.incrementAndGet();
            return Stream.empty();
        }
    }
}
