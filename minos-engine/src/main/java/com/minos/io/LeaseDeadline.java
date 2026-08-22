package com.minos.io;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * One bounded acquisition budget shared by the JVM lock and the OS lock of a lease.
 *
 * <p>Both locks draw from the same deadline so a caller can never wait longer than the timeout it
 * asked for, however the wait is split between the two layers. Every waiting path in this package
 * is expressed against this budget: an unbounded lock acquisition anywhere in a lease would
 * reintroduce the indefinite stall this type exists to prevent.</p>
 */
final class LeaseDeadline {

    private static final long POLL_MILLIS = 50L;

    private final Duration timeout;
    private final long startedAtNanos;
    private final long timeoutNanos;

    private LeaseDeadline(Duration timeout, long startedAtNanos, long timeoutNanos) {
        this.timeout = timeout;
        this.startedAtNanos = startedAtNanos;
        this.timeoutNanos = timeoutNanos;
    }

    static LeaseDeadline after(Duration timeout) {
        Duration wait = Objects.requireNonNull(timeout, "timeout");
        if (wait.isZero() || wait.isNegative()) {
            throw new IllegalArgumentException("lock timeout must be positive");
        }
        long nanos;
        try {
            nanos = wait.toNanos();
        } catch (ArithmeticException overflow) {
            nanos = Long.MAX_VALUE;
        }
        return new LeaseDeadline(wait, System.nanoTime(), nanos);
    }

    long remainingNanos() {
        long elapsed = System.nanoTime() - startedAtNanos;
        if (elapsed <= 0L) return timeoutNanos;
        if (elapsed >= timeoutNanos) return 0L;
        return timeoutNanos - elapsed;
    }

    /**
     * Sleeps for one bounded poll interval, or fails if the budget is exhausted. The budget is
     * re-checked after the sleep so an expired deadline can never grant one extra attempt.
     */
    void pauseBeforeRetry(String description) throws IOException {
        long remainingNanos = remainingNanos();
        if (remainingNanos <= 0L) throw timeout(description);
        long convertedMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
        long sleepMillis = boundedSleepMillis(convertedMillis);
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for " + description, interrupted);
        }
        if (remainingNanos() <= 0L) throw timeout(description);
    }

    IOException timeout(String description) {
        return new IOException("timed out waiting for " + description + " after " + timeout);
    }

    private static long boundedSleepMillis(long convertedMillis) {
        if (convertedMillis <= 0L) return 1L;
        if (convertedMillis > POLL_MILLIS) return POLL_MILLIS;
        return convertedMillis;
    }
}
