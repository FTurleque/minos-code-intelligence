package com.minos.bootstrap.application;

import com.minos.application.ProjectIndexStateReconciler;
import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.IndexStateStoreException;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.ProjectIndexState;
import com.minos.storage.local.orchestration.FileIndexStateStore;
import com.minos.storage.local.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AUD-QUA-03: the reconciliation catches the failure type of the index state port and only it. A programming error
 * in a store goes up as it is, instead of being presented as an I/O failure of the index state.
 */
class ProjectIndexStateReconcilerErrorContractTest {

    @TempDir Path temp;

    @Test
    void aNullPointerExceptionOfTheStoreIsNotRequalifiedAsAnIoFailure() throws Exception {
        NullPointerException defect = new NullPointerException("store defect");
        ProjectIndexStateReconciler reconciler = reconciler(() -> defect);

        NullPointerException raised = assertThrows(NullPointerException.class,
                () -> reconciler.observeState(UUID.randomUUID()));

        assertSame(defect, raised, "no \"failed to read project index state\" wraps a programming error");
    }

    @Test
    void aStoreFailureStaysTheExistingIoFailureThatNamesTheProject() throws Exception {
        IndexStateStoreException failure = new IndexStateStoreException("cannot read MINOS project state");
        ProjectIndexStateReconciler reconciler = reconciler(() -> failure);
        UUID projectId = UUID.randomUUID();

        IOException raised = assertThrows(IOException.class, () -> reconciler.observeState(projectId));

        assertEquals("failed to read project index state for project " + projectId, raised.getMessage());
        assertSame(failure, raised.getCause());
    }

    private ProjectIndexStateReconciler reconciler(Supplier<RuntimeException> onRead) throws IOException {
        FileIndexStateStore delegate = new FileIndexStateStore(temp.resolve("state"));
        IndexStateStore failing = new IndexStateStore() {
            @Override public Optional<ProjectIndexState> findProjectState(UUID projectId) { throw onRead.get(); }
            @Override public Optional<IndexingRun> findRun(UUID runId) { return delegate.findRun(runId); }
            @Override public List<IndexingRun> listRuns(UUID projectId) { return delegate.listRuns(projectId); }
            @Override public void saveProjectState(ProjectIndexState state) { delegate.saveProjectState(state); }
            @Override public void saveRun(IndexingRun run) { delegate.saveRun(run); }
            @Override public ProjectLease acquireProjectLease(UUID projectId) {
                return delegate.acquireProjectLease(projectId);
            }
        };
        return new ProjectIndexStateReconciler(new FileSymbolSnapshotStore(temp.resolve("snapshots")), failing);
    }
}
