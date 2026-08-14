package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
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

class IndexingPreRunReconciliationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-14T09:00:00Z"), ZoneOffset.UTC);

    @Test
    void newIndexingRunUsesAuthoritativeSnapshotAfterPreviousMetadataCommitFailure(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        Path artifact = Files.writeString(root.resolve("index.scip"), "index");
        InMemoryIndexStateStore store = new InMemoryIndexStateStore();
        store.saveProjectState(new ProjectIndexState(projectId, ProjectIndexState.Availability.READY,
                Optional.of("snapshot-old"), Optional.empty(), CLOCK.instant(), Optional.empty()));
        SnapshotPromoter promoter = new SnapshotPromoter() {
            @Override
            public void promote(UUID id, UUID runId, String stagedSnapshotId) { }

            @Override
            public Optional<String> activeSnapshotId(UUID id) {
                return Optional.of("snapshot-authoritative");
            }
        };
        IndexingLifecycleService service = new IndexingLifecycleService(
                List.of(new IndexingRuntimePorts.IndexerExecutor() {
                    public String indexerId() { return "java-indexer"; }
                    public IndexingRuntimePorts.IndexingArtifact execute(IndexingRuntimePorts.IndexingExecutionRequest request) {
                        return new IndexingRuntimePorts.IndexingArtifact(Language.JAVA, "java-indexer", artifact);
                    }
                }),
                request -> "snapshot-next",
                promoter,
                store,
                CLOCK
        );

        IndexingRun result = service.execute(projectId, root,
                new IndexerNegotiationResult(List.of(selection()), Set.of(), List.of()));

        assertEquals(IndexingRun.Status.SUCCEEDED, result.status());
        assertEquals(Optional.of("snapshot-authoritative"), result.activeSnapshotBefore());
        assertEquals(Optional.of("snapshot-next"), result.activeSnapshotAfter());
        assertEquals(Optional.of("snapshot-next"), store.findProjectState(projectId).orElseThrow().activeSnapshotId());
        assertEquals(ProjectIndexState.Availability.READY,
                store.findProjectState(projectId).orElseThrow().availability());
    }

    private static IndexerSelection selection() {
        IndexerDescriptor descriptor = new IndexerDescriptor("java-indexer", "1", "java-indexer",
                Set.of(Language.JAVA), Set.of(),
                EnumSet.of(IndexerCapability.SYMBOLS, IndexerCapability.REFERENCES),
                IndexerQualification.QUALIFIED, 100, List.of());
        return new IndexerSelection(Language.JAVA, descriptor);
    }
}
