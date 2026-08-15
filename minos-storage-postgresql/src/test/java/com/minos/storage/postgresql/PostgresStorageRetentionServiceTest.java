package com.minos.storage.postgresql;

import com.minos.incremental.ProjectFingerprint;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.ProjectIndexState;
import com.minos.storage.PersistentRetentionPolicy;
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

class PostgresStorageRetentionServiceTest extends PostgresTestSupport {

    @TempDir Path tempDir;

    @Test
    void compactsTransactionallyAndPreservesEveryActiveReference() throws Exception {
        UUID projectId = UUID.randomUUID();
        PostgresCodeKnowledgeSnapshotStore knowledge = new PostgresCodeKnowledgeSnapshotStore(connections, tempDir);
        PostgresFingerprintSnapshotStore fingerprints =
                new PostgresFingerprintSnapshotStore(connections, new PostgresJsonCodec());
        PostgresIndexStateStore states = new PostgresIndexStateStore(connections, new PostgresJsonCodec());
        ProjectFingerprint fingerprint = emptyFingerprint();

        for (int index = 0; index < 6; index++) {
            String snapshotId = "snapshot-" + index;
            knowledge.publish(projectId, snapshotId, List.of(), List.of(), List.of());
            fingerprints.publish(projectId, snapshotId, fingerprint);
            fingerprints.promote(projectId, snapshotId);
        }
        fingerprints.promote(projectId, "snapshot-4");
        List<IndexingRun> runs = new ArrayList<>();
        for (int index = 0; index < 25; index++) runs.add(run(projectId, IndexingRun.Status.SUCCEEDED, index));
        for (int index = 0; index < 12; index++) runs.add(run(projectId, IndexingRun.Status.FAILED, 100 + index));
        for (IndexingRun run : runs) states.saveRun(run);
        UUID protectedLatest = runs.get(25).id();
        states.saveProjectState(new ProjectIndexState(
                projectId, ProjectIndexState.Availability.READY,
                Optional.of("snapshot-0"), Optional.of(protectedLatest),
                Instant.parse("2026-02-01T00:00:00Z"), Optional.of("ready")));

        var result = new PostgresStorageRetentionService(connections)
                .compact(projectId, PersistentRetentionPolicy.DEFAULT);

        assertTrue(result.deletedKnowledgeSnapshots() > 0);
        assertTrue(result.deletedFingerprintSnapshots() > 0);
        assertTrue(result.deletedIndexingRuns() > 0);
        assertEquals(4, count("knowledge_snapshots", projectId));
        assertEquals(5, count("fingerprint_snapshots", projectId));
        assertEquals(31, states.listRuns(projectId).size());
        assertTrue(states.findRun(protectedLatest).isPresent());
        assertEquals("snapshot-5", new PostgresCodeKnowledgeSnapshotStore(connections, tempDir)
                .loadActiveKnowledge(projectId).orElseThrow().snapshotId());
        assertEquals("snapshot-4", new PostgresFingerprintSnapshotStore(connections, new PostgresJsonCodec())
                .loadActive(projectId).orElseThrow().indexSnapshotId());
        assertEquals("snapshot-0",
                new PostgresIndexStateStore(connections, new PostgresJsonCodec())
                        .findProjectState(projectId).orElseThrow().activeSnapshotId().orElseThrow());
        assertEquals(protectedLatest,
                new PostgresIndexStateStore(connections, new PostgresJsonCodec())
                        .findProjectState(projectId).orElseThrow().latestRunId().orElseThrow());
    }

    private int count(String table, UUID projectId) throws Exception {
        return connections.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM " + table + " WHERE project_id=?")) {
                statement.setObject(1, projectId);
                try (var result = statement.executeQuery()) {
                    result.next();
                    return result.getInt(1);
                }
            }
        });
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
