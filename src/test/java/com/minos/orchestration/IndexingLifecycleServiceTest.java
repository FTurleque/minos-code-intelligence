package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexingRuntimePorts.IndexSnapshotStageRequest;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotStager;
import com.minos.orchestration.ProjectIndexState.Availability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexingLifecycleServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-23T07:15:00Z"), ZoneOffset.UTC);

    @Test
    void promotesOnlyAfterEverySelectedIndexerAndStagingSucceed(@TempDir Path root) throws IOException {
        Path javaArtifact = Files.writeString(root.resolve("java.scip"), "java");
        Path typescriptArtifact = Files.writeString(root.resolve("typescript.scip"), "typescript");
        InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        RecordingStager stager = new RecordingStager("snapshot-new");
        RecordingPromoter promoter = new RecordingPromoter(false);
        IndexingLifecycleService service = service(
                List.of(
                        successfulExecutor("java-indexer", Language.JAVA, javaArtifact),
                        successfulExecutor("typescript-indexer", Language.TYPESCRIPT, typescriptArtifact)
                ),
                stager,
                promoter,
                store
        );
        UUID projectId = UUID.randomUUID();

        IndexingRun run = service.execute(projectId, root, negotiation(
                selection("java-indexer", Language.JAVA),
                selection("typescript-indexer", Language.TYPESCRIPT)
        ));

        assertEquals(IndexingRun.Status.SUCCEEDED, run.status());
        assertEquals(IndexingRun.Phase.COMPLETED, run.phase());
        assertEquals(2, run.executions().size());
        assertEquals(Optional.of("snapshot-new"), run.stagedSnapshotId());
        assertEquals(Optional.of("snapshot-new"), run.activeSnapshotAfter());
        assertEquals(1, stager.calls.get());
        assertEquals(2, stager.lastRequest.get().artifacts().size());
        assertEquals(1, promoter.calls.get());

        ProjectIndexState state = service.projectState(projectId);
        assertEquals(Availability.READY, state.availability());
        assertEquals(Optional.of("snapshot-new"), state.activeSnapshotId());
        assertEquals(Optional.of(run.id()), state.latestRunId());
    }

    @Test
    void oneProviderFailurePreventsStagingAndPromotionForTheWholeProject(@TempDir Path root) throws IOException {
        Path javaArtifact = Files.writeString(root.resolve("java.scip"), "java");
        InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        RecordingStager stager = new RecordingStager("snapshot-should-not-exist");
        RecordingPromoter promoter = new RecordingPromoter(false);
        IndexingLifecycleService service = service(
                List.of(
                        successfulExecutor("java-indexer", Language.JAVA, javaArtifact),
                        failingExecutor("typescript-indexer", "typescript indexing failed")
                ),
                stager,
                promoter,
                store
        );
        UUID projectId = UUID.randomUUID();

        IndexingRun run = service.execute(projectId, root, negotiation(
                selection("java-indexer", Language.JAVA),
                selection("typescript-indexer", Language.TYPESCRIPT)
        ));

        assertEquals(IndexingRun.Status.FAILED, run.status());
        assertEquals(IndexingRun.Phase.PROVIDER_EXECUTION, run.phase());
        assertEquals(1, run.executions().size());
        assertEquals(0, stager.calls.get());
        assertEquals(0, promoter.calls.get());
        assertEquals(Availability.FAILED, service.projectState(projectId).availability());
        assertTrue(service.projectState(projectId).activeSnapshotId().isEmpty());
    }

    @Test
    void failedRefreshKeepsPreviousSnapshotAndMarksProjectStale(@TempDir Path root) throws IOException {
        Path artifact = Files.writeString(root.resolve("java.scip"), "java");
        InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        UUID projectId = UUID.randomUUID();
        store.saveProjectState(new ProjectIndexState(
                projectId,
                Availability.READY,
                Optional.of("snapshot-old"),
                Optional.empty(),
                CLOCK.instant(),
                Optional.of("previous successful snapshot")
        ));
        RecordingStager stager = new RecordingStager("snapshot-new");
        RecordingPromoter promoter = new RecordingPromoter(true);
        IndexingLifecycleService service = service(
                List.of(successfulExecutor("java-indexer", Language.JAVA, artifact)),
                stager,
                promoter,
                store
        );

        IndexingRun run = service.execute(projectId, root, negotiation(
                selection("java-indexer", Language.JAVA)
        ));

        assertEquals(IndexingRun.Status.FAILED, run.status());
        assertEquals(IndexingRun.Phase.PROMOTION, run.phase());
        assertEquals(Optional.of("snapshot-new"), run.stagedSnapshotId());
        assertEquals(Optional.of("snapshot-old"), run.activeSnapshotBefore());
        assertEquals(Optional.of("snapshot-old"), run.activeSnapshotAfter());
        assertEquals(1, stager.calls.get());
        assertEquals(1, promoter.calls.get());

        ProjectIndexState state = service.projectState(projectId);
        assertEquals(Availability.STALE, state.availability());
        assertEquals(Optional.of("snapshot-old"), state.activeSnapshotId());
    }

    @Test
    void refusesIncompleteNegotiationBeforeStartingARun(@TempDir Path root) {
        InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        IndexingLifecycleService service = service(
                List.of(),
                request -> "unused",
                (projectId, runId, stagedSnapshotId) -> {
                },
                store
        );
        UUID projectId = UUID.randomUUID();
        IndexerNegotiationResult incomplete = new IndexerNegotiationResult(
                List.of(),
                Set.of(Language.JAVA),
                List.of()
        );

        assertThrows(IllegalArgumentException.class, () -> service.execute(projectId, root, incomplete));
        assertTrue(store.listRuns(projectId).isEmpty());
        assertEquals(Availability.NEVER_INDEXED, service.projectState(projectId).availability());
    }

    private static IndexingLifecycleService service(
            List<IndexerExecutor> executors,
            SnapshotStager stager,
            SnapshotPromoter promoter,
            InMemoryIndexStateStore store
    ) {
        return new IndexingLifecycleService(executors, stager, promoter, store, CLOCK);
    }

    private static IndexerExecutor successfulExecutor(String id, Language language, Path artifact) {
        return new IndexerExecutor() {
            @Override
            public String indexerId() {
                return id;
            }

            @Override
            public IndexingArtifact execute(IndexingExecutionRequest request) {
                return new IndexingArtifact(language, id, artifact);
            }
        };
    }

    private static IndexerExecutor failingExecutor(String id, String message) {
        return new IndexerExecutor() {
            @Override
            public String indexerId() {
                return id;
            }

            @Override
            public IndexingArtifact execute(IndexingExecutionRequest request) {
                throw new IllegalStateException(message);
            }
        };
    }

    private static IndexerNegotiationResult negotiation(IndexerSelection... selections) {
        return new IndexerNegotiationResult(List.of(selections), Set.of(), List.of());
    }

    private static IndexerSelection selection(String id, Language language) {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                id,
                "1.0",
                id,
                Set.of(language),
                Set.of(),
                EnumSet.of(IndexerCapability.SYMBOLS, IndexerCapability.REFERENCES),
                IndexerQualification.QUALIFIED,
                100,
                List.of()
        );
        return new IndexerSelection(language, descriptor);
    }

    private static final class RecordingStager implements SnapshotStager {
        private final String snapshotId;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<IndexSnapshotStageRequest> lastRequest = new AtomicReference<>();

        private RecordingStager(String snapshotId) {
            this.snapshotId = snapshotId;
        }

        @Override
        public String stage(IndexSnapshotStageRequest request) {
            calls.incrementAndGet();
            lastRequest.set(request);
            return snapshotId;
        }
    }

    private static final class RecordingPromoter implements SnapshotPromoter {
        private final boolean fail;
        private final AtomicInteger calls = new AtomicInteger();

        private RecordingPromoter(boolean fail) {
            this.fail = fail;
        }

        @Override
        public void promote(UUID projectId, UUID runId, String stagedSnapshotId) {
            calls.incrementAndGet();
            if (fail) {
                throw new IllegalStateException("promotion failed");
            }
        }
    }
}
