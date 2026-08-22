package com.minos.storage;

import com.minos.incremental.FileProjectFingerprintSnapshotStore;
import com.minos.incremental.ProjectFingerprint;
import com.minos.orchestration.FileIndexStateStore;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.ProjectIndexState;
import com.minos.store.FileSymbolSnapshotStore;
import com.minos.store.SnapshotIntegrityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalStorageRetentionServiceTest {

    @Test
    void compactsAllDurableHistoriesAndPreservesActivePointersAcrossReopen(@TempDir Path home)
            throws Exception {
        Path knowledgeRoot = home.resolve("symbol-snapshots");
        Path indexRoot = home.resolve("index-state");
        Path fingerprintRoot = home.resolve("fingerprint-snapshots");
        FileSymbolSnapshotStore knowledge = new FileSymbolSnapshotStore(knowledgeRoot);
        FileIndexStateStore states = new FileIndexStateStore(indexRoot);
        FileProjectFingerprintSnapshotStore fingerprints =
                new FileProjectFingerprintSnapshotStore(fingerprintRoot);
        UUID projectId = UUID.randomUUID();
        ProjectFingerprint fingerprint = emptyFingerprint();

        for (int index = 0; index < 6; index++) {
            String snapshotId = "snapshot-" + index;
            knowledge.publish(projectId, snapshotId, List.of(), List.of(), List.of());
            fingerprints.publish(projectId, snapshotId, fingerprint);
            fingerprints.promote(projectId, snapshotId);
        }
        fingerprints.promote(projectId, "snapshot-4");

        List<IndexingRun> runs = new ArrayList<>();
        for (int index = 0; index < 25; index++) {
            runs.add(run(projectId, IndexingRun.Status.SUCCEEDED, index));
        }
        for (int index = 0; index < 12; index++) {
            runs.add(run(projectId, IndexingRun.Status.FAILED, 100 + index));
        }
        for (IndexingRun run : runs) states.saveRun(run);
        UUID protectedLatest = runs.get(25).id(); // deliberately older than the ten retained failures
        states.saveProjectState(new ProjectIndexState(
                projectId, ProjectIndexState.Availability.READY,
                Optional.of("snapshot-0"), Optional.of(protectedLatest),
                Instant.parse("2026-02-01T00:00:00Z"), Optional.of("ready")));

        StorageRetentionService.RetentionResult result = new LocalStorageRetentionService(
                home, knowledgeRoot, indexRoot, fingerprints, states)
                .compact(projectId, PersistentRetentionPolicy.DEFAULT);

        assertTrue(result.deletedKnowledgeSnapshots() > 0);
        assertTrue(result.deletedFingerprintSnapshots() > 0);
        assertTrue(result.deletedIndexingRuns() > 0);
        assertEquals(4, knowledge.retentionService().listSnapshotFiles(projectId).size());
        assertEquals(5, fingerprints.listIndexSnapshotIds(projectId).size());
        String stateProtectedPrefix = "snapshot-"
                + new SnapshotIntegrityService().logicalIdHash("snapshot-0") + "-";
        assertTrue(knowledge.retentionService().listSnapshotFiles(projectId).stream()
                .anyMatch(name -> name.startsWith(stateProtectedPrefix)));
        assertTrue(fingerprints.listIndexSnapshotIds(projectId).contains("snapshot-0"));
        assertTrue(fingerprints.listIndexSnapshotIds(projectId).contains("snapshot-5"));
        assertEquals(31, states.listRuns(projectId).size());
        assertTrue(states.findRun(protectedLatest).isPresent());

        FileSymbolSnapshotStore reopenedKnowledge = new FileSymbolSnapshotStore(knowledgeRoot);
        FileProjectFingerprintSnapshotStore reopenedFingerprints =
                new FileProjectFingerprintSnapshotStore(fingerprintRoot);
        FileIndexStateStore reopenedStates = new FileIndexStateStore(indexRoot);
        assertEquals("snapshot-5", reopenedKnowledge.loadActiveKnowledge(projectId).orElseThrow().snapshotId());
        assertEquals("snapshot-4", reopenedFingerprints.loadActive(projectId).orElseThrow().indexSnapshotId());
        assertEquals(protectedLatest,
                reopenedStates.findProjectState(projectId).orElseThrow().latestRunId().orElseThrow());
        assertEquals("snapshot-0",
                reopenedStates.findProjectState(projectId).orElseThrow().activeSnapshotId().orElseThrow());
    }

    private static IndexingRun run(UUID projectId, IndexingRun.Status status, int sequence) {
        Instant created = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(sequence * 60L);
        boolean succeeded = status == IndexingRun.Status.SUCCEEDED;
        return new IndexingRun(
                UUID.randomUUID(), projectId, status,
                succeeded ? IndexingRun.Phase.COMPLETED : IndexingRun.Phase.PROMOTION,
                created, Optional.of(created.plusSeconds(10)), List.of(), Optional.empty(), Optional.empty(),
                succeeded ? Optional.of("snapshot-run-" + sequence) : Optional.empty(),
                succeeded ? Optional.empty() : Optional.of("failed"));
    }

    private static ProjectFingerprint emptyFingerprint() {
        String emptySha256 = "e3b0c44298fc1c149afbf4c8996fb924"
                + "27ae41e4649b934ca495991b7852b855";
        return new ProjectFingerprint(emptySha256, emptySha256, List.of());
    }
}
