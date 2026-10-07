package com.minos.orchestration;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Baseline légère M1 du stockage d'état d'indexation.
 */
public final class InMemoryIndexStateStore implements IndexStateStore {

    private final ConcurrentMap<UUID, ProjectIndexState> projectStates = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, IndexingRun> runs = new ConcurrentHashMap<>();
    private final Object leaseMonitor = new Object();
    private final ConcurrentMap<UUID, LeaseState> projectLeases = new ConcurrentHashMap<>();

    @Override
    public Optional<ProjectIndexState> findProjectState(UUID projectId) {
        return Optional.ofNullable(projectStates.get(Objects.requireNonNull(projectId, "projectId")));
    }

    @Override
    public Optional<IndexingRun> findRun(UUID runId) {
        return Optional.ofNullable(runs.get(Objects.requireNonNull(runId, "runId")));
    }

    @Override
    public List<IndexingRun> listRuns(UUID projectId) {
        Objects.requireNonNull(projectId, "projectId");
        return runs.values().stream()
                .filter(run -> run.projectId().equals(projectId))
                .sorted(Comparator.comparing(IndexingRun::createdAt).thenComparing(IndexingRun::id))
                .toList();
    }

    @Override
    public void saveProjectState(ProjectIndexState state) {
        Objects.requireNonNull(state, "state");
        projectStates.put(state.projectId(), state);
    }

    @Override
    public void saveRun(IndexingRun run) {
        Objects.requireNonNull(run, "run");
        runs.put(run.id(), run);
    }

    @Override
    public ProjectLease acquireProjectLease(UUID projectId) {
        UUID id = Objects.requireNonNull(projectId, "projectId");
        LeaseState state;
        synchronized (leaseMonitor) {
            state = projectLeases.computeIfAbsent(id, ignored -> new LeaseState());
            state.references++;
        }
        state.lock.lock();
        AtomicBoolean closed = new AtomicBoolean();
        LeaseState acquired = state;
        Thread owner = Thread.currentThread();
        return () -> {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("in-memory project lease must be released by its owner thread");
            }
            if (!closed.compareAndSet(false, true)) return;
            acquired.lock.unlock();
            synchronized (leaseMonitor) {
                acquired.references--;
                if (acquired.references < 0) {
                    throw new IllegalStateException("in-memory project lease reference underflow");
                }
                if (acquired.references == 0) projectLeases.remove(id, acquired);
            }
        };
    }

    private static final class LeaseState {
        private final ReentrantLock lock = new ReentrantLock(true);
        private int references;
    }
}
