package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery.Language;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertTrue(Files.isRegularFile(root.resolve("state/runs").resolve(projectId.toString()).resolve(runId + ".properties")));
    }

    @Test
    void rejectsProjectStateWhoseEmbeddedIdentityDoesNotMatchLookupKey() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID otherProjectId = UUID.randomUUID();
        Path stateRoot = root.resolve("project-identity-state");
        FileIndexStateStore store = new FileIndexStateStore(stateRoot);
        store.saveProjectState(ProjectIndexState.neverIndexed(projectId, Instant.parse("2026-08-14T12:00:00Z")));

        Path file = stateRoot.resolve("projects").resolve(projectId + ".properties");
        Files.writeString(file, Files.readString(file).replace(
                "projectId=" + projectId,
                "projectId=" + otherProjectId));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> store.findProjectState(projectId));
        assertTrue(failure.getMessage().contains("identity mismatch"));
    }

    @Test
    void rejectsRunWhoseEmbeddedIdentityDoesNotMatchFilenameOnLookupAndListing() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID otherRunId = UUID.randomUUID();
        Path stateRoot = root.resolve("run-identity-state");
        FileIndexStateStore store = new FileIndexStateStore(stateRoot);
        store.saveRun(new IndexingRun(
                runId,
                projectId,
                IndexingRun.Status.SUCCEEDED,
                IndexingRun.Phase.COMPLETED,
                Instant.parse("2026-08-14T12:00:00Z"),
                Optional.of(Instant.parse("2026-08-14T12:01:00Z")),
                List.of(),
                Optional.of("snapshot-run-identity"),
                Optional.empty(),
                Optional.of("snapshot-run-identity"),
                Optional.of("completed")));

        Path file = stateRoot.resolve("runs").resolve(projectId.toString()).resolve(runId + ".properties");
        Files.writeString(file, Files.readString(file).replace(
                "id=" + runId,
                "id=" + otherRunId));

        assertThrows(IllegalStateException.class, () -> store.findRun(runId));
        assertThrows(IllegalStateException.class, () -> store.listRuns(projectId));
    }

    @Test
    void corruptRunFromAnotherProjectDoesNotBreakProjectScopedListing() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID otherProjectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID otherRunId = UUID.randomUUID();
        Path stateRoot = root.resolve("isolated-run-state");
        FileIndexStateStore store = new FileIndexStateStore(stateRoot);
        store.saveRun(run(runId, projectId));
        store.saveRun(run(otherRunId, otherProjectId));

        Path other = stateRoot.resolve("runs").resolve(otherProjectId.toString()).resolve(otherRunId + ".properties");
        Files.writeString(other, "corrupt=true\n");

        assertEquals(List.of(runId), store.listRuns(projectId).stream().map(IndexingRun::id).toList());
    }

    @Test
    void migratesValidLegacyRunIntoProjectDirectory() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Path stateRoot = root.resolve("legacy-run-state");
        FileIndexStateStore store = new FileIndexStateStore(stateRoot);
        store.saveRun(run(runId, projectId));
        Path scoped = stateRoot.resolve("runs").resolve(projectId.toString()).resolve(runId + ".properties");
        Path legacy = stateRoot.resolve("runs").resolve(runId + ".properties");
        Files.move(scoped, legacy);
        Files.deleteIfExists(scoped.getParent());

        FileIndexStateStore reopened = new FileIndexStateStore(stateRoot);

        assertTrue(Files.notExists(legacy));
        assertTrue(Files.isRegularFile(scoped));
        assertEquals(List.of(runId), reopened.listRuns(projectId).stream().map(IndexingRun::id).toList());
    }

    @Test
    void rejectsOversizedPersistedMetadataBeforePropertiesParsing() throws Exception {
        UUID projectId = UUID.randomUUID();
        Path stateRoot = root.resolve("state");
        FileIndexStateStore store = new FileIndexStateStore(stateRoot);
        Files.write(stateRoot.resolve("projects").resolve(projectId + ".properties"),
                new byte[4 * 1024 * 1024 + 1]);

        assertThrows(UncheckedIOException.class, () -> store.findProjectState(projectId));
    }

    private static IndexingRun run(UUID runId, UUID projectId) {
        return new IndexingRun(
                runId,
                projectId,
                IndexingRun.Status.SUCCEEDED,
                IndexingRun.Phase.COMPLETED,
                Instant.parse("2026-08-14T12:00:00Z"),
                Optional.of(Instant.parse("2026-08-14T12:01:00Z")),
                List.of(),
                Optional.of("snapshot-" + runId),
                Optional.empty(),
                Optional.of("snapshot-" + runId),
                Optional.of("completed"));
    }
}
