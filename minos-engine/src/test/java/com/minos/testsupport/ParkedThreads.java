package com.minos.testsupport;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Proves that a concurrent caller is waiting for a lock of this JVM instead of assuming it after a fixed
 * delay (AUD-TST-10).
 *
 * <p>A caller is "parked in" a class when its thread is {@link Thread.State#WAITING} or
 * {@link Thread.State#TIMED_WAITING} with a frame of that class on its stack: a {@code ReentrantLock}
 * acquisition and the bounded retry pause of a file lease both park the thread there. The observation has
 * a deadline: a caller that finishes first, or that is still not parked when the deadline expires, fails
 * the test with a message; it never passes by default.</p>
 */
public final class ParkedThreads {

    private ParkedThreads() {
    }

    /** Waits until {@code thread} is parked in {@code frameClass}; fails at the deadline. */
    public static void awaitParkedIn(Thread thread, String frameClass, Duration timeout) {
        awaitParkedIn(thread, frameClass, () -> false, timeout);
    }

    /**
     * Waits until {@code thread} is parked in {@code frameClass}. Fails as soon as {@code finished} reports
     * that the caller completed (or its thread terminated) without having been seen waiting, and fails when
     * {@code timeout} expires.
     */
    public static void awaitParkedIn(Thread thread, String frameClass, BooleanSupplier finished, Duration timeout) {
        Objects.requireNonNull(thread, "thread");
        Objects.requireNonNull(frameClass, "frameClass");
        Objects.requireNonNull(finished, "finished");
        long deadline = System.nanoTime() + Objects.requireNonNull(timeout, "timeout").toNanos();
        while (!isParkedIn(thread, frameClass)) {
            if (finished.getAsBoolean() || thread.getState() == Thread.State.TERMINATED) {
                fail(thread.getName() + " completed before it was observed waiting in " + frameClass);
            }
            if (System.nanoTime() - deadline >= 0L) {
                fail(thread.getName() + " was not observed waiting in " + frameClass + " within " + timeout
                        + " (state " + thread.getState() + ")");
            }
            Thread.onSpinWait();
        }
    }

    /** Whether {@code thread} is currently waiting with a frame of {@code frameClass} on its stack. */
    public static boolean isParkedIn(Thread thread, String frameClass) {
        Thread.State state = thread.getState();
        if (state != Thread.State.WAITING && state != Thread.State.TIMED_WAITING) return false;
        for (StackTraceElement frame : thread.getStackTrace()) {
            if (frame.getClassName().equals(frameClass)) return true;
        }
        return false;
    }
}
