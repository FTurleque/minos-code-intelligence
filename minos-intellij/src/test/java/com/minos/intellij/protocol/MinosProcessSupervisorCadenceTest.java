package com.minos.intellij.protocol;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plugin cannot share code with the runtime, so it carries its own copy of the ownership polling rule and
 * its own test of it (AUD-PERF-08): after the first second of the process's life, a window of two seconds
 * sees at most 30 enumerations of its descendants, and at least one so a stopped tracker cannot pass for a
 * saving. This pins the copy to the same bound as {@code ProcessOwnershipTrackerCadenceTest} in the runtime.
 */
class MinosProcessSupervisorCadenceTest {

    /** The initial phase is one second; the margin keeps the measured window clear of its end. */
    private static final long AFTER_INITIAL_PHASE_MILLIS = 1_300L;
    private static final long WINDOW_MILLIS = 2_000L;
    private static final int MAXIMUM_ENUMERATIONS_IN_WINDOW = 30;

    @Test
    void aLiveProcessIsEnumeratedRarelyOnceTheInitialPhaseIsOver() throws Exception {
        CountingProcess root = new CountingProcess();
        MinosProcessSupervisor supervisor = new MinosProcessSupervisor(root);
        int inWindow;
        try {
            Thread.sleep(AFTER_INITIAL_PHASE_MILLIS);
            int before = root.enumerations.get();
            Thread.sleep(WINDOW_MILLIS);
            inWindow = root.enumerations.get() - before;
        } finally {
            root.die();
            try {
                supervisor.close();
            } catch (MinosProtocolException tolerated) {
                // Cleanup diagnostics of the fixture are not the subject here.
            }
        }

        assertTrue(inWindow >= 1, "a stopped tracker is not a saving: " + inWindow);
        assertTrue(inWindow <= MAXIMUM_ENUMERATIONS_IN_WINDOW,
                "expected at most " + MAXIMUM_ENUMERATIONS_IN_WINDOW + " enumerations in "
                        + WINDOW_MILLIS + " ms, got " + inWindow);
    }

    /** A live process whose descendants are counted and always empty. */
    private static final class CountingProcess extends Process {
        private final AtomicInteger enumerations = new AtomicInteger();
        private final AtomicBoolean alive = new AtomicBoolean(true);

        void die() {
            alive.set(false);
        }

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
