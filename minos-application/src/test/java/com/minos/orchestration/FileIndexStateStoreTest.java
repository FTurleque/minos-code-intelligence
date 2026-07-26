package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery.Language;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileIndexStateStoreTest {

    @TempDir
    Path root;

    @Test
    void persistsProjectStateAndCompletedRunAcrossReopen() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-07-24T10:00:00Z");
        Instant completedAt = Instant.parse("2026-07-24T10:01:00Z");
        Path artifact = root.resolve("run/index.scip").toAbsolutePath().normalize();

        FileIndexStateStore first = new FileIndexStateStore(root.resolve("state"));
        first.saveProjectState(new ProjectIndexState(
                projectId,
                ProjectIndexState.Availability.READY,
                Optional.of("snapshot-1"),
                Optional.of(runId),
                completedAt,
                Optional.of("ready")
        ));
        first.saveRun(new IndexingRun(
                runId,
                projectId,
                IndexingRun.Status.SUCCEEDED,
                IndexingRun.Phase.COMPLETED,
                createdAt,
                Optional.of(completedAt),
                List.of(new IndexingRun.IndexerExecution(Language.JAVA, "scip-java", artifact)),
                Optional.of("snapshot-1"),
                Optional.empty(),
                Optional.of("snapshot-1"),
                Optional.of("completed")
        ));

        FileIndexStateStore reopened = new FileIndexStateStore(root.resolve("state"));
        ProjectIndexState state = reopened.findProjectState(projectId).orElseThrow();
        IndexingRun run = reopened.findRun(runId).orElseThrow();

        assertEquals(ProjectIndexState.Availability.READY, state.availability());
        assertEquals(Optional.of("snapshot-1"), state.activeSnapshotId());
        assertEquals(IndexingRun.Status.SUCCEEDED, run.status());
        assertEquals(artifact, run.executions().getFirst().finalArtifact());
        assertEquals(List.of(runId), reopened.listRuns(projectId).stream().map(IndexingRun::id).toList());
        assertTrue(reopened.listRuns(UUID.randomUUID()).isEmpty());
    }
}
