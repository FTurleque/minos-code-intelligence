package com.minos.testsupport;

import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The waiting probe proves a wait when there is one and fails, with a message, when there is none. */
class ParkedThreadsTest {

    private static final String LOCKER = Locker.class.getName();
    private static final Duration DEADLINE = Duration.ofSeconds(10);

    @Test
    void observesACallerWaitingForAHeldLock() throws Exception {
        ReentrantLock lock = new ReentrantLock();
        lock.lock();
        Thread waiter = new Thread(() -> Locker.lockAndRelease(lock), "parked-threads-waiter");
        try {
            waiter.start();

            ParkedThreads.awaitParkedIn(waiter, LOCKER, DEADLINE);

            assertTrue(ParkedThreads.isParkedIn(waiter, LOCKER));
            assertTrue(lock.hasQueuedThread(waiter), "the observed thread is in the queue of the held lock");
        } finally {
            lock.unlock();
            waiter.join(TimeUnit.SECONDS.toMillis(10));
        }
        assertFalse(waiter.isAlive());
    }

    @Test
    void failsWhenTheCallerCompletedWithoutWaiting() throws Exception {
        Thread caller = new Thread(() -> Locker.lockAndRelease(new ReentrantLock()), "parked-threads-free");
        caller.start();
        caller.join(TimeUnit.SECONDS.toMillis(10));

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> ParkedThreads.awaitParkedIn(caller, LOCKER, DEADLINE));

        assertTrue(failure.getMessage().contains("completed before it was observed waiting in " + LOCKER),
                failure.getMessage());
    }

    @Test
    void failsAsSoonAsTheCallerReportsItsCompletion() {
        Thread caller = Thread.currentThread();
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> ParkedThreads.awaitParkedIn(caller, LOCKER, () -> true, DEADLINE));

        assertTrue(failure.getMessage().contains("completed before"), failure.getMessage());
    }

    @Test
    void failsAtTheDeadlineWhenTheCallerWaitsElsewhere() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Thread elsewhere = new Thread(() -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }, "parked-threads-elsewhere");
        Duration deadline = Duration.ofMillis(100);
        elsewhere.start();
        try {
            AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                    () -> ParkedThreads.awaitParkedIn(elsewhere, LOCKER, deadline));

            assertTrue(failure.getMessage().contains("was not observed waiting in " + LOCKER + " within PT0.1S"),
                    failure.getMessage());
        } finally {
            release.countDown();
            elsewhere.join(TimeUnit.SECONDS.toMillis(10));
        }
    }

    @Test
    void aRunningThreadIsNotParked() {
        assertFalse(ParkedThreads.isParkedIn(Thread.currentThread(), ParkedThreadsTest.class.getName()));
    }

    /** Owns the frame the probe looks for. */
    private static final class Locker {
        private Locker() {
        }

        static void lockAndRelease(ReentrantLock lock) {
            lock.lock();
            lock.unlock();
        }
    }
}
