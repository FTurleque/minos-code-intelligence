package com.minos.orchestration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexRunRetentionServiceTest {

    @Test
    void compactionKeepsNewestRunsAndProtectsLatestRunId(@TempDir Path root) throws Exception {
        FileIndexStateStore store = new FileIndexStateStore(root);
        UUID projectId = UUID.randomUUID();

        IndexingRun successOld = run(projectId, IndexingRun.Status.SUCCEEDED, 1);
        IndexingRun successNew = run(projectId, IndexingRun.Status.SUCCEEDED, 2);
        IndexingRun failedOld = run(projectId, IndexingRun.Status.FAILED, 3);
        IndexingRun failedLatest = run(projectId, IndexingRun.Status.FAILED, 4);
        for (IndexingRun run : List.of(successOld, successNew, failedOld, failedLatest)) {
            store.saveRun(run);
        }
        store.saveProjectState(new ProjectIndexState(
                projectId,
                ProjectIndexState.Availability.STALE,
                Optional.of("snapshot-2"),
                Optional.of(failedLatest.id()),
                Instant.parse("2026-01-05T00:00:00Z"),
                Optional.of("latest failure remains observable")
        ));

        IndexRunRetentionService.RetentionResult result = new IndexRunRetentionService(root, store)
                .compact(projectId, new IndexRunRetentionPolicy(1, 0));

        assertTrue(result.retainedRunIds().contains(successNew.id()));
        assertTrue(result.retainedRunIds().contains(failedLatest.id()));
        assertFalse(result.retainedRunIds().contains(successOld.id()));
        assertFalse(result.retainedRunIds().contains(failedOld.id()));
        assertEquals(failedLatest.id(), result.protectedLatestRunId());
        assertTrue(store.findRun(successNew.id()).isPresent());
        assertTrue(store.findRun(failedLatest.id()).isPresent());
        assertTrue(store.findRun(successOld.id()).isEmpty());
        assertTrue(store.findRun(failedOld.id()).isEmpty());
    }

    private static IndexingRun run(UUID projectId, IndexingRun.Status status, int day) {
        Instant instant = Instant.parse("2026-01-0" + day + "T00:00:00Z");
        boolean success = status == IndexingRun.Status.SUCCEEDED;
        return new IndexingRun(
                UUID.randomUUID(),
                projectId,
                status,
                success ? IndexingRun.Phase.COMPLETED : IndexingRun.Phase.PROMOTION,
                instant,
                Optional.of(instant.plusSeconds(10)),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                success ? Optional.of("snapshot-" + day) : Optional.empty(),
                success ? Optional.empty() : Optional.of("failed")
        );
    }
}
