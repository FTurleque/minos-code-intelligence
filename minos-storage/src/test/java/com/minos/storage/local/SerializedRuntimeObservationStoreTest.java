package com.minos.storage.local;

import com.minos.dynamic.CorrelatedRuntimeSession;
import com.minos.dynamic.RuntimeObservationStore;
import com.minos.testsupport.ParkedThreads;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerializedRuntimeObservationStoreTest {
    private static final UUID PROJECT = new UUID(0L, 0L);
    /** Hashes to another lock stripe than {@link #PROJECT}. */
    private static final UUID OTHER_STRIPE_PROJECT = new UUID(0L, 1L);
    private static final Duration WAIT = Duration.ofSeconds(10);

    @Test
    void refusesMissingDelegate() {
        NullPointerException failure = assertThrows(NullPointerException.class,
                () -> new SerializedRuntimeObservationStore(null));
        assertEquals("delegate", failure.getMessage());
    }

    @Test
    void refusesNullArgumentsWithoutReachingTheDelegate() {
        RecordingStore delegate = new RecordingStore();
        SerializedRuntimeObservationStore store = new SerializedRuntimeObservationStore(delegate);

        NullPointerException session = assertThrows(NullPointerException.class, () -> store.save(null));
        assertEquals("session", session.getMessage());
        NullPointerException find = assertThrows(NullPointerException.class, () -> store.find(null, "run-1"));
        assertEquals("projectId", find.getMessage());
        NullPointerException list = assertThrows(NullPointerException.class, () -> store.list(null));
        assertEquals("projectId", list.getMessage());
        assertTrue(delegate.calls.isEmpty(), "a refused call must never reach the file store: " + delegate.calls);
    }

    @Test
    void forwardsEveryOperationAndItsResultUnchanged() throws Exception {
        RecordingStore delegate = new RecordingStore();
        SerializedRuntimeObservationStore store = new SerializedRuntimeObservationStore(delegate);
        CorrelatedRuntimeSession session = RuntimeSessionFixtures.session(PROJECT, "run-1");

        RuntimeObservationStore.SaveResult saved = store.save(session);
        Optional<CorrelatedRuntimeSession> found = store.find(PROJECT, "run-1");
        List<CorrelatedRuntimeSession> listed = store.list(PROJECT);

        assertSame(session, saved.session());
        assertFalse(saved.alreadyPresent());
        assertSame(session, found.orElseThrow());
        assertEquals(List.of(session), listed);
        assertEquals(List.of("save:" + PROJECT, "find:" + PROJECT + ":run-1", "list:" + PROJECT), delegate.calls);
    }

    @Test
    void releasesTheProjectLockWhenTheDelegateFails() throws Exception {
        RecordingStore delegate = new RecordingStore();
        delegate.failure = new IOException("disk unavailable");
        SerializedRuntimeObservationStore store = new SerializedRuntimeObservationStore(delegate);

        IOException failure = assertThrows(IOException.class, () -> store.list(PROJECT));
        assertSame(delegate.failure, failure);

        delegate.failure = null;
        // Another thread must be able to take the same stripe: a lock held after a failure would block it.
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<List<CorrelatedRuntimeSession>> next = executor.submit(() -> store.list(PROJECT));
            assertEquals(List.of(), next.get(WAIT.toSeconds(), TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @RepeatedTest(50)
    void serializesCallsOfOneProjectButNotOfAnotherStripe() throws Exception {
        RecordingStore delegate = new RecordingStore();
        CountDownLatch holderInside = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        delegate.blockFind(holderInside, releaseHolder);
        SerializedRuntimeObservationStore store = new SerializedRuntimeObservationStore(delegate);
        AtomicReference<Thread> waiter = new AtomicReference<>();
        CountDownLatch waiterStarted = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<Optional<CorrelatedRuntimeSession>> holder = executor.submit(() -> store.find(PROJECT, "run-1"));
            assertTrue(holderInside.await(WAIT.toSeconds(), TimeUnit.SECONDS), "holder never reached the delegate");

            Future<List<CorrelatedRuntimeSession>> sameProject = executor.submit(() -> {
                waiter.set(Thread.currentThread());
                waiterStarted.countDown();
                return store.list(PROJECT);
            });
            assertTrue(waiterStarted.await(WAIT.toSeconds(), TimeUnit.SECONDS));
            ParkedThreads.awaitParkedIn(waiter.get(), "java.util.concurrent.locks.ReentrantLock",
                    sameProject::isDone, WAIT);
            assertFalse(delegate.calls.contains("list:" + PROJECT),
                    "a second call of the same project must not reach the delegate while the first one runs");

            Future<List<CorrelatedRuntimeSession>> otherStripe = executor.submit(() -> store.list(OTHER_STRIPE_PROJECT));
            assertEquals(List.of(), otherStripe.get(WAIT.toSeconds(), TimeUnit.SECONDS),
                    "a project of another stripe must not wait for the held lock");
            assertFalse(holder.isDone());

            releaseHolder.countDown();
            assertTrue(holder.get(WAIT.toSeconds(), TimeUnit.SECONDS).isEmpty());
            assertEquals(List.of(), sameProject.get(WAIT.toSeconds(), TimeUnit.SECONDS));
        } finally {
            releaseHolder.countDown();
            executor.shutdownNow();
        }
        List<String> calls = delegate.calls;
        assertTrue(calls.indexOf("find:" + PROJECT + ":run-1") < calls.indexOf("list:" + PROJECT), calls::toString);
    }

    /** In-memory delegate that records the order of the calls it receives and can hold one find call. */
    private static final class RecordingStore implements RuntimeObservationStore {
        private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        private final List<CorrelatedRuntimeSession> sessions = Collections.synchronizedList(new ArrayList<>());
        private volatile IOException failure;
        private volatile CountDownLatch findEntered;
        private volatile CountDownLatch findRelease;

        void blockFind(CountDownLatch entered, CountDownLatch release) {
            this.findEntered = entered;
            this.findRelease = release;
        }

        @Override
        public SaveResult save(CorrelatedRuntimeSession session) throws IOException {
            calls.add("save:" + session.session().projectId());
            failIfRequested();
            sessions.add(session);
            return new SaveResult(session, false);
        }

        @Override
        public Optional<CorrelatedRuntimeSession> find(UUID projectId, String sessionId) throws IOException {
            calls.add("find:" + projectId + ":" + sessionId);
            failIfRequested();
            if (findEntered != null) {
                findEntered.countDown();
                try {
                    if (!findRelease.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
                        throw new IOException("held find was never released");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("held find interrupted", interrupted);
                }
            }
            return sessions.stream()
                    .filter(value -> value.session().projectId().equals(projectId))
                    .filter(value -> value.session().sessionId().equals(sessionId))
                    .findFirst();
        }

        @Override
        public List<CorrelatedRuntimeSession> list(UUID projectId) throws IOException {
            calls.add("list:" + projectId);
            failIfRequested();
            return sessions.stream().filter(value -> value.session().projectId().equals(projectId)).toList();
        }

        private void failIfRequested() throws IOException {
            IOException pending = failure;
            if (pending != null) throw pending;
        }
    }
}
