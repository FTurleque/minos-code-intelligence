package com.minos.storage;

import com.minos.dynamic.CorrelatedRuntimeSession;
import com.minos.dynamic.RuntimeObservationStore;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** Bounded JVM serialization layer placed before file-backed runtime observation locks. */
final class SerializedRuntimeObservationStore implements RuntimeObservationStore {
    private static final int STRIPES = 64;
    private final ReentrantLock[] locks = locks();
    private final RuntimeObservationStore delegate;

    SerializedRuntimeObservationStore(RuntimeObservationStore delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public SaveResult save(CorrelatedRuntimeSession session) throws IOException {
        Objects.requireNonNull(session, "session");
        return under(session.session().projectId(), () -> delegate.save(session));
    }

    @Override
    public Optional<CorrelatedRuntimeSession> find(UUID projectId, String sessionId) throws IOException {
        return under(projectId, () -> delegate.find(projectId, sessionId));
    }

    @Override
    public List<CorrelatedRuntimeSession> list(UUID projectId) throws IOException {
        return under(projectId, () -> delegate.list(projectId));
    }

    private <T> T under(UUID projectId, IoOperation<T> operation) throws IOException {
        ReentrantLock lock = locks[Math.floorMod(Objects.requireNonNull(projectId, "projectId").hashCode(), locks.length)];
        lock.lock();
        try {
            return operation.run();
        } finally {
            lock.unlock();
        }
    }

    private static ReentrantLock[] locks() {
        ReentrantLock[] result = new ReentrantLock[STRIPES];
        for (int index = 0; index < result.length; index++) result[index] = new ReentrantLock();
        return result;
    }

    @FunctionalInterface
    private interface IoOperation<T> { T run() throws IOException; }
}
