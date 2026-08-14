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
        first.saveRun(run(runId, projectId, createdAt, completedAt, artifact));

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

    @Test
    void lifecycleLeaseIsReentrantOnOwnerThread() throws Exception {
        FileIndexStateStore store = new FileIndexStateStore(root.resolve("reentrant-state"));
        UUID projectId = UUID.randomUUID();

        try (IndexStateStore.ProjectLease outer = store.acquireProjectLease(projectId)) {
            try (IndexStateStore.ProjectLease nested = store.acquireProjectLease(projectId)) {
                store.saveProjectState(ProjectIndexState.neverIndexed(projectId, Instant.EPOCH));
            }
            assertTrue(store.findProjectState(projectId).isPresent());
        }

        try (IndexStateStore.ProjectLease reacquired = store.acquireProjectLease(projectId)) {
            assertTrue(store.findProjectState(projectId).isPresent());
        }
    }

    @Test
    void migratesLegacyFlatRunFilesOnReopen() throws Exception {
        Path stateRoot = root.resolve("legacy-state");
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        FileIndexStateStore initial = new FileIndexStateStore(stateRoot);
        initial.saveRun(run(runId, projectId, Instant.EPOCH, Instant.EPOCH.plusSeconds(1),
                root.resolve("legacy.scip")));

        Path partitioned = stateRoot.resolve("runs").resolve(projectId.toString()).resolve(runId + ".properties");
        Path legacy = stateRoot.resolve("runs").resolve(runId + ".properties");
        Files.move(partitioned, legacy);
        Files.deleteIfExists(partitioned.getParent());

        FileIndexStateStore reopened = new FileIndexStateStore(stateRoot);

        assertTrue(Files.isRegularFile(partitioned));
        assertTrue(Files.notExists(legacy));
        assertEquals(List.of(runId), reopened.listRuns(projectId).stream().map(IndexingRun::id).toList());
    }

    @Test
    void corruptRunFromAnotherProjectCannotPoisonListing() throws Exception {
        Path stateRoot = root.resolve("isolated-runs");
        UUID healthyProject = UUID.randomUUID();
        UUID corruptProject = UUID.randomUUID();
        UUID healthyRun = UUID.randomUUID();
        UUID corruptRun = UUID.randomUUID();
        FileIndexStateStore store = new FileIndexStateStore(stateRoot);
        store.saveRun(run(healthyRun, healthyProject, Instant.EPOCH, Instant.EPOCH.plusSeconds(1),
                root.resolve("healthy.scip")));
        store.saveRun(run(corruptRun, corruptProject, Instant.EPOCH, Instant.EPOCH.plusSeconds(1),
                root.resolve("corrupt.scip")));

        Path corruptFile = stateRoot.resolve("runs").resolve(corruptProject.toString())
                .resolve(corruptRun + ".properties");
        Files.writeString(corruptFile, "not-a-valid-run");

        assertEquals(List.of(healthyRun), store.listRuns(healthyProject).stream().map(IndexingRun::id).toList());
        assertThrows(IllegalStateException.class, () -> store.listRuns(corruptProject));
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
        store.saveRun(run(runId, projectId, Instant.parse("2026-08-14T12:00:00Z"),
                Instant.parse("2026-08-14T12:01:00Z"), root.resolve("identity.scip")));

        Path file = stateRoot.resolve("runs").resolve(projectId.toString()).resolve(runId + ".properties");
        Files.writeString(file, Files.readString(file).replace(
                "id=" + runId,
                "id=" + otherRunId));

        assertThrows(IllegalStateException.class, () -> store.findRun(runId));
        assertThrows(IllegalStateException.class, () -> store.listRuns(projectId));
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

    private static IndexingRun run(
            UUID runId,
            UUID projectId,
            Instant createdAt,
            Instant completedAt,
            Path artifact
    ) {
        return new IndexingRun(
                runId,
                projectId,
                IndexingRun.Status.SUCCEEDED,
                IndexingRun.Phase.COMPLETED,
                createdAt,
                Optional.of(completedAt),
                List.of(new IndexingRun.IndexerExecution(Language.JAVA, "scip-java",
                        artifact.toAbsolutePath().normalize())),
                Optional.of("snapshot-1"),
                Optional.empty(),
                Optional.of("snapshot-1"),
                Optional.of("completed")
        );
    }
}
