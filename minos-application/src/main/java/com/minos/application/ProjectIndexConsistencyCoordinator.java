package com.minos.application;

import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.ProjectIndexState;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;

import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the durable project-index view with the active SnapshotStore pointer as commit authority.
 *
 * <p>The snapshot publication/promotion and ProjectIndexState persistence are separate storage
 * operations for some backends. A crash or metadata write failure can therefore leave a committed
 * active snapshot with stale project metadata. This coordinator makes that state non-observable:
 * it reads the active snapshot around the metadata read, retries when the pointer moves, repairs a
 * stale/missing metadata record from the stable active snapshot, and fails closed when repair cannot
 * be persisted.</p>
 */
final class ProjectIndexConsistencyCoordinator {

    private static final int MAX_STABILITY_ATTEMPTS = 4;
    private static final int MAX_STATE_SAVE_ATTEMPTS = 2;

    private final CodeKnowledgeSnapshotStore snapshotStore;
    private final IndexStateStore stateStore;
    private final Clock clock;

    ProjectIndexConsistencyCoordinator(CodeKnowledgeSnapshotStore snapshotStore, IndexStateStore stateStore) {
        this(snapshotStore, stateStore, Clock.systemUTC());
    }

    ProjectIndexConsistencyCoordinator(
            CodeKnowledgeSnapshotStore snapshotStore,
            IndexStateStore stateStore,
            Clock clock
    ) {
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    Resolution resolve(UUID projectId) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        for (int attempt = 1; attempt <= MAX_STABILITY_ATTEMPTS; attempt++) {
            Optional<CodeKnowledgeSnapshot> before = snapshotStore.loadActiveKnowledge(projectId);
            Optional<ProjectIndexState> stored = loadState(projectId);
            Optional<CodeKnowledgeSnapshot> after = snapshotStore.loadActiveKnowledge(projectId);
            if (!sameActiveSnapshot(before, after)) continue;

            if (after.isPresent()) {
                CodeKnowledgeSnapshot active = after.orElseThrow();
                String activeSnapshotId = active.snapshotId();
                if (stored.isPresent()
                        && stored.orElseThrow().activeSnapshotId().filter(activeSnapshotId::equals).isPresent()) {
                    return new Resolution(after, stored.orElseThrow(), false);
                }

                ProjectIndexState reconciled = new ProjectIndexState(
                        projectId,
                        ProjectIndexState.Availability.READY,
                        Optional.of(activeSnapshotId),
                        stored.flatMap(ProjectIndexState::latestRunId),
                        clock.instant(),
                        Optional.of("project index state reconciled from authoritative active snapshot"));
                saveState(reconciled);

                Optional<CodeKnowledgeSnapshot> verified = snapshotStore.loadActiveKnowledge(projectId);
                if (!sameActiveSnapshot(after, verified)) continue;
                return new Resolution(verified, reconciled, true);
            }

            if (stored.isPresent() && stored.orElseThrow().activeSnapshotId().isPresent()) {
                throw new IOException(
                        "project index metadata references active snapshot '"
                                + stored.orElseThrow().activeSnapshotId().orElseThrow()
                                + "' but SnapshotStore has no active snapshot for project " + projectId);
            }

            ProjectIndexState state = stored.orElseGet(
                    () -> ProjectIndexState.neverIndexed(projectId, clock.instant()));
            return new Resolution(Optional.empty(), state, false);
        }
        throw new IOException(
                "active snapshot changed repeatedly while resolving project index state for project " + projectId);
    }

    private Optional<ProjectIndexState> loadState(UUID projectId) throws IOException {
        try {
            return stateStore.findProjectState(projectId);
        } catch (RuntimeException failure) {
            throw new IOException("unable to read project index state for project " + projectId, failure);
        }
    }

    private void saveState(ProjectIndexState state) throws IOException {
        RuntimeException firstFailure = null;
        for (int attempt = 0; attempt < MAX_STATE_SAVE_ATTEMPTS; attempt++) {
            try {
                stateStore.saveProjectState(state);
                return;
            } catch (RuntimeException failure) {
                if (firstFailure == null) firstFailure = failure;
                else firstFailure.addSuppressed(failure);
            }
        }
        throw new IOException(
                "active snapshot is committed but project index state reconciliation could not be persisted",
                firstFailure);
    }

    private static boolean sameActiveSnapshot(
            Optional<CodeKnowledgeSnapshot> left,
            Optional<CodeKnowledgeSnapshot> right
    ) {
        return left.map(CodeKnowledgeSnapshot::snapshotId)
                .equals(right.map(CodeKnowledgeSnapshot::snapshotId));
    }

    record Resolution(
            Optional<CodeKnowledgeSnapshot> activeSnapshot,
            ProjectIndexState indexState,
            boolean reconciled
    ) {
        Resolution {
            activeSnapshot = Objects.requireNonNull(activeSnapshot, "activeSnapshot");
            Objects.requireNonNull(indexState, "indexState");
        }
    }
}
