package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.io.CommitUncertainException;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexingRuntimePorts.ActiveSnapshotObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexingCommitRecoveryTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-13T16:00:00Z"), ZoneOffset.UTC);

    @Test
    void metadataFailureAfterPromotionNeverRestoresPreviousSnapshot(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        Path artifact = Files.writeString(root.resolve("index.scip"), "index");
        FailingSuccessRunStore store = new FailingSuccessRunStore();
        store.state = new ProjectIndexState(projectId, ProjectIndexState.Availability.READY,
                Optional.of("snapshot-old"), Optional.empty(), CLOCK.instant(), Optional.empty());
        IndexerSelection selection = selection();
        IndexingLifecycleService service = new IndexingLifecycleService(
                List.of(new IndexingRuntimePorts.IndexerExecutor() {
                    public String indexerId() { return "java-indexer"; }
                    public IndexingRuntimePorts.IndexingArtifact execute(IndexingRuntimePorts.IndexingExecutionRequest request) {
                        return new IndexingRuntimePorts.IndexingArtifact(Language.JAVA, "java-indexer", artifact);
                    }
                }),
                request -> "snapshot-new",
                (id, runId, snapshotId) -> { },
                store,
                CLOCK
        );

        IndexingRun result = service.execute(projectId, root,
                new IndexerNegotiationResult(List.of(selection), Set.of(), List.of()));

        assertEquals(IndexingRun.Status.FAILED, result.status());
        assertEquals(Optional.of("snapshot-new"), result.activeSnapshotAfter());
        assertEquals(ProjectIndexState.Availability.READY, store.state.availability());
        assertEquals(Optional.of("snapshot-new"), store.state.activeSnapshotId());
    }

    @Test
    void lostPromotionAcknowledgementIsRecoveredFromAuthoritativeSnapshot(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        Path artifact = Files.writeString(root.resolve("uncertain.scip"), "index");
        InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        store.saveProjectState(new ProjectIndexState(projectId, ProjectIndexState.Availability.READY,
                Optional.of("snapshot-old"), Optional.empty(), CLOCK.instant(), Optional.empty()));
        UncertainPromoter promoter = new UncertainPromoter("snapshot-old");
        IndexingLifecycleService service = new IndexingLifecycleService(
                List.of(new IndexingRuntimePorts.IndexerExecutor() {
                    public String indexerId() { return "java-indexer"; }
                    public IndexingRuntimePorts.IndexingArtifact execute(IndexingRuntimePorts.IndexingExecutionRequest request) {
                        return new IndexingRuntimePorts.IndexingArtifact(Language.JAVA, "java-indexer", artifact);
                    }
                }),
                request -> "snapshot-new",
                promoter,
                store,
                CLOCK
        );

        IndexingRun result = service.execute(projectId, root,
                new IndexerNegotiationResult(List.of(selection()), Set.of(), List.of()));

        assertEquals(IndexingRun.Status.SUCCEEDED, result.status());
        assertEquals(Optional.of("snapshot-new"), result.activeSnapshotAfter());
        assertTrue(result.message().orElseThrow().contains("lost durability acknowledgement"));
        ProjectIndexState state = store.findProjectState(projectId).orElseThrow();
        assertEquals(ProjectIndexState.Availability.READY, state.availability());
        assertEquals(Optional.of("snapshot-new"), state.activeSnapshotId());
    }

    private static IndexerSelection selection() {
        IndexerDescriptor descriptor = new IndexerDescriptor("java-indexer", "1", "java-indexer",
                Set.of(Language.JAVA), Set.of(),
                EnumSet.of(IndexerCapability.SYMBOLS, IndexerCapability.REFERENCES),
                IndexerQualification.QUALIFIED, 100, List.of());
        return new IndexerSelection(Language.JAVA, descriptor);
    }

    private static final class UncertainPromoter implements IndexingRuntimePorts.SnapshotPromoter {
        private String active;
        private UncertainPromoter(String active) { this.active = active; }
        @Override
        public void promote(UUID projectId, UUID runId, String stagedSnapshotId) throws Exception {
            active = stagedSnapshotId;
            throw new CommitUncertainException("synthetic post-commit acknowledgement failure");
        }
        @Override
        public ActiveSnapshotObservation observeActiveSnapshot(UUID projectId) {
            return ActiveSnapshotObservation.active(active);
        }
    }

    private static final class FailingSuccessRunStore implements IndexStateStore {
        private ProjectIndexState state;
        private IndexingRun run;
        private boolean failed;
        public Optional<ProjectIndexState> findProjectState(UUID id) { return Optional.ofNullable(state); }
        public Optional<IndexingRun> findRun(UUID id) { return Optional.ofNullable(run).filter(value -> value.id().equals(id)); }
        public List<IndexingRun> listRuns(UUID id) { return run == null ? List.of() : List.of(run); }
        public void saveProjectState(ProjectIndexState value) { state = value; }
        public void saveRun(IndexingRun value) {
            if (!failed && value.status() == IndexingRun.Status.SUCCEEDED) {
                failed = true;
                throw new IllegalStateException("synthetic metadata failure");
            }
            run = value;
        }
        public ProjectLease acquireProjectLease(UUID id) { return () -> { }; }
    }
}
