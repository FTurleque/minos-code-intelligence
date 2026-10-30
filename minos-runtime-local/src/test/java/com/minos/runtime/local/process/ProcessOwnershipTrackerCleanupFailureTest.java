package com.minos.runtime.local.process;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chemins de nettoyage de {@link ProcessOwnershipTracker} atteints sans processus réel : les observations
 * sont injectées par {@code remember}, la racine est un {@link Process} de test.
 */
class ProcessOwnershipTrackerCleanupFailureTest {

    /** Un PID qui n'est jamais celui de la JVM hôte ; sans date de démarrage, aucun PID n'est résolu par l'OS. */
    private static long foreignPid() {
        return ProcessHandle.current().pid() == Long.MAX_VALUE ? Long.MAX_VALUE - 1 : Long.MAX_VALUE;
    }

    @Test
    void aDescendantWithoutStartInstantIsTerminatedThroughItsObservedHandleOnly() throws Exception {
        StubProcess root = new StubProcess(false);
        FakeHandle descendant = new FakeHandle(foreignPid(), true);

        ProcessOwnershipTracker tracker = new ProcessOwnershipTracker(root);
        tracker.remember(List.of(descendant));
        assertTrue(tracker.ownsPid(descendant.pid()));
        assertFalse(tracker.ownsPid(descendant.pid() - 7));

        tracker.terminate();

        assertEquals(1, descendant.gracefulRequests.get(),
                "a live owned descendant is first asked to stop gracefully, once");
        assertEquals(0, descendant.forcedRequests.get(),
                "a descendant that honoured the graceful request must not be killed");
        assertFalse(descendant.isAlive());

        tracker.close();
        assertEquals(1, descendant.gracefulRequests.get(), "a completed cleanup is not replayed");
    }

    /** Rejoué 50 fois : le résultat ne doit pas dépendre de l'avancement du fil de suivi. */
    @Test
    void aTreeThatSurvivesForcedTerminationIsReportedAndTheInterruptionIsPreserved() {
        for (int attempt = 0; attempt < 50; attempt++) {
            StubProcess root = new StubProcess(true);
            FakeHandle survivor = new FakeHandle(foreignPid(), false);
            ProcessOwnershipTracker tracker = new ProcessOwnershipTracker(root);
            tracker.remember(List.of(survivor));

            IOException failure;
            boolean interruptedAfterwards;
            Thread.currentThread().interrupt();
            try {
                failure = assertThrows(IOException.class, tracker::terminate);
            } finally {
                interruptedAfterwards = Thread.interrupted();
            }

            assertTrue(interruptedAfterwards, "cleanup must restore the interruption it consumed");
            assertEquals("provider process cleanup did not complete cleanly", failure.getMessage());
            List<Throwable> reasons = new ArrayList<>(List.of(failure.getSuppressed()));
            reasons.add(failure.getCause());
            assertTrue(reasons.stream().anyMatch(InterruptedException.class::isInstance),
                    "the interruption of a bounded wait is one of the reported reasons");
            assertTrue(reasons.stream().map(Throwable::getMessage).anyMatch(
                    "provider process tree has 1 root + 1 owned descendant(s) still alive after forced termination"
                            ::equals),
                    "survivors must be counted, never silently abandoned: " + reasons);
            assertTrue(root.gracefulRequests.get() >= 1, "the root is asked to stop gracefully first");
            assertTrue(root.forcedRequests.get() >= 1, "then forcibly");
            assertTrue(survivor.gracefulRequests.get() >= 1);
            assertTrue(survivor.forcedRequests.get() >= 2,
                    "a surviving descendant is killed again after the tracker stopped");
        }
    }

    /** Rejoué 50 fois : le test attend l'observation fautive par un loquet, jamais par un délai. */
    @Test
    void aFailedOwnershipObservationIsReportedByCleanupInsteadOfBeingLost() throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            StubProcess root = new StubProcess(false);
            root.failObservationsOfTheTracker.set(true);
            ProcessOwnershipTracker tracker = new ProcessOwnershipTracker(root);
            assertTrue(root.trackerObserved.await(30, TimeUnit.SECONDS),
                    "the ownership tracker never observed the process tree");

            IOException failure = assertThrows(IOException.class, tracker::terminate);

            assertEquals("provider process cleanup did not complete cleanly", failure.getMessage());
            assertEquals("provider process ownership tracking failed", failure.getCause().getMessage());
            assertEquals(IllegalStateException.class, failure.getCause().getCause().getClass());
        }
    }

    /** Racine factice : vivante ou non selon le scénario, sans descendant visible par l'OS. */
    private static final class StubProcess extends Process {
        private final boolean alive;
        private final AtomicInteger gracefulRequests = new AtomicInteger();
        private final AtomicInteger forcedRequests = new AtomicInteger();
        private final AtomicBoolean failObservationsOfTheTracker = new AtomicBoolean();
        private final CountDownLatch trackerObserved = new CountDownLatch(1);

        private StubProcess(boolean alive) {
            this.alive = alive;
        }

        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public boolean isAlive() { return alive; }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            if (alive) throw new IllegalThreadStateException("still running");
            return 0;
        }

        @Override
        public void destroy() {
            gracefulRequests.incrementAndGet();
        }

        @Override
        public Process destroyForcibly() {
            forcedRequests.incrementAndGet();
            return this;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            if (failObservationsOfTheTracker.get() && Thread.currentThread().isVirtual()) {
                trackerObserved.countDown();
                throw new IllegalStateException("process table unavailable");
            }
            return Stream.empty();
        }
    }

    /** Descendant observé sans date de démarrage : seul ce handle fait autorité pour le terminer. */
    private static final class FakeHandle implements ProcessHandle {
        private final long pid;
        private final boolean diesOnGracefulRequest;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicInteger gracefulRequests = new AtomicInteger();
        private final AtomicInteger forcedRequests = new AtomicInteger();

        private FakeHandle(long pid, boolean diesOnGracefulRequest) {
            this.pid = pid;
            this.diesOnGracefulRequest = diesOnGracefulRequest;
        }

        @Override public long pid() { return pid; }
        @Override public Optional<ProcessHandle> parent() { return Optional.empty(); }
        @Override public Stream<ProcessHandle> children() { return Stream.empty(); }
        @Override public Stream<ProcessHandle> descendants() { return Stream.empty(); }
        @Override public CompletableFuture<ProcessHandle> onExit() { return new CompletableFuture<>(); }
        @Override public boolean supportsNormalTermination() { return true; }
        @Override public boolean isAlive() { return alive.get(); }
        @Override public int compareTo(ProcessHandle other) { return Long.compare(pid, other.pid()); }

        @Override
        public boolean destroy() {
            gracefulRequests.incrementAndGet();
            if (diesOnGracefulRequest) alive.set(false);
            return true;
        }

        @Override
        public boolean destroyForcibly() {
            forcedRequests.incrementAndGet();
            return true;
        }

        @Override
        public Info info() {
            return new Info() {
                @Override public Optional<String> command() { return Optional.empty(); }
                @Override public Optional<String> commandLine() { return Optional.empty(); }
                @Override public Optional<String[]> arguments() { return Optional.empty(); }
                @Override public Optional<Instant> startInstant() { return Optional.empty(); }
                @Override public Optional<Duration> totalCpuDuration() { return Optional.empty(); }
                @Override public Optional<String> user() { return Optional.empty(); }
            };
        }
    }
}
