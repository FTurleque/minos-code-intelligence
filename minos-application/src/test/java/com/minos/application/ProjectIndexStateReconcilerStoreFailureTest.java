package com.minos.application;

import com.minos.domain.Relationship;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolOccurrence;
import com.minos.orchestration.InMemoryIndexStateStore;
import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.IndexStateStoreException;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.ProjectIndexState;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;
import com.minos.store.SnapshotQueryView;
import com.minos.store.SymbolSnapshot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AUD-QUA-03: each read or write of the index state port that fails with {@link IndexStateStoreException} becomes
 * the I/O failure of the reconciliation that names the project and keeps the store failure as its cause.
 */
class ProjectIndexStateReconcilerStoreFailureTest {

    private static final String ACTIVE = "snapshot-1";

    private final UUID projectId = UUID.randomUUID();
    private final IndexStateStoreException failure = new IndexStateStoreException("store failure");

    @Test
    void aFailingProjectStateReadNamesTheProject() {
        FailingStore store = new FailingStore();
        store.failRead = true;

        IOException raised = assertThrows(IOException.class, () -> reconciler(store).observeState(projectId));

        assertEquals("failed to read project index state for project " + projectId, raised.getMessage());
        assertSame(failure, raised.getCause());
    }

    @Test
    void aFailingRunListingNamesTheProject() {
        FailingStore store = new FailingStore();
        store.failRuns = true;

        IOException raised = assertThrows(IOException.class, () -> reconciler(store).observeState(projectId));

        assertEquals("failed to read indexing runs for project " + projectId, raised.getMessage());
        assertSame(failure, raised.getCause());
    }

    @Test
    void aFailingLeaseAcquisitionNamesTheProject() {
        FailingStore store = new FailingStore();
        store.failLease = true;

        IOException raised = assertThrows(IOException.class, () -> reconciler(store).reconcileState(projectId));

        assertEquals("failed to acquire project lifecycle lease for metadata reconciliation: " + projectId,
                raised.getMessage());
        assertSame(failure, raised.getCause());
    }

    @Test
    void aFailingRepairWriteNamesTheProject() {
        FailingStore store = new FailingStore();
        store.failSave = true;

        IOException raised = assertThrows(IOException.class, () -> reconciler(store).reconcileState(projectId));

        assertEquals("active snapshot metadata reconciliation failed for project " + projectId, raised.getMessage());
        assertSame(failure, raised.getCause());
    }

    private ProjectIndexStateReconciler reconciler(IndexStateStore store) {
        return new ProjectIndexStateReconciler(new ActiveOnlySnapshotStore(), store);
    }

    /** An index state store that fails on the operations a test selects and delegates the others. */
    private final class FailingStore implements IndexStateStore {
        private final InMemoryIndexStateStore delegate = new InMemoryIndexStateStore();
        boolean failRead;
        boolean failRuns;
        boolean failLease;
        boolean failSave;

        @Override public Optional<ProjectIndexState> findProjectState(UUID id) {
            if (failRead) throw failure;
            return delegate.findProjectState(id);
        }

        @Override public Optional<IndexingRun> findRun(UUID runId) { return delegate.findRun(runId); }

        @Override public List<IndexingRun> listRuns(UUID id) {
            if (failRuns) throw failure;
            return delegate.listRuns(id);
        }

        @Override public void saveProjectState(ProjectIndexState state) {
            if (failSave) throw failure;
            delegate.saveProjectState(state);
        }

        @Override public void saveRun(IndexingRun run) { delegate.saveRun(run); }

        @Override public ProjectLease acquireProjectLease(UUID id) {
            if (failLease) throw failure;
            return delegate.acquireProjectLease(id);
        }
    }

    /** A snapshot store whose only answer is the identity of an active snapshot that no state references yet. */
    private static final class ActiveOnlySnapshotStore implements CodeKnowledgeSnapshotStore {
        @Override public Optional<String> activeSnapshotId(UUID projectId) { return Optional.of(ACTIVE); }

        @Override public SymbolSnapshot publish(UUID projectId, String snapshotId, Collection<Symbol> symbols) {
            throw new UnsupportedOperationException();
        }

        @Override public CodeKnowledgeSnapshot publish(UUID projectId, String snapshotId, Collection<Symbol> symbols,
                Collection<SymbolOccurrence> occurrences, Collection<Relationship> relationships) {
            throw new UnsupportedOperationException();
        }

        @Override public Optional<SymbolSnapshot> loadActive(UUID projectId) { return Optional.empty(); }

        @Override public Optional<CodeKnowledgeSnapshot> loadActiveKnowledge(UUID projectId) {
            return Optional.empty();
        }

        @Override public Optional<SnapshotQueryView> loadActiveQueryView(UUID projectId) { return Optional.empty(); }
    }
}
