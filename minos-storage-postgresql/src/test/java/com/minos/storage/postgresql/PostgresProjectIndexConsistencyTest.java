package com.minos.storage.postgresql;

import com.minos.application.ProjectInspectionService;
import com.minos.discovery.ProjectDiscoveryService;
import com.minos.orchestration.ProjectIndexState;
import com.minos.registry.RegisteredProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PostgresProjectIndexConsistencyTest extends PostgresTestSupport {

    @Test
    void inspectionReconcilesPostgresMetadataFromCommittedActiveSnapshot(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Path projectRoot = temp.resolve("project");
        Files.createDirectories(projectRoot);

        try (PostgresStorageBackend backend = new PostgresStorageBackend(connections, home)) {
            RegisteredProject project = backend.projectRegistry().registerProject(projectRoot, "postgres-project");
            backend.snapshotStore().publish(project.id(), "snapshot-n", List.of(), List.of(), List.of());
            backend.indexStateStore().saveProjectState(new ProjectIndexState(
                    project.id(),
                    ProjectIndexState.Availability.READY,
                    Optional.of("snapshot-n"),
                    Optional.empty(),
                    Instant.parse("2026-08-14T08:00:00Z"),
                    Optional.of("baseline")));

            backend.snapshotStore().publish(project.id(), "snapshot-n-plus-1", List.of(), List.of(), List.of());
            assertEquals("snapshot-n", backend.indexStateStore().findProjectState(project.id())
                    .orElseThrow().activeSnapshotId().orElseThrow());

            ProjectInspectionService inspection = new ProjectInspectionService(
                    home,
                    backend.projectRegistry(),
                    backend.snapshotStore(),
                    backend.indexStateStore(),
                    new ProjectDiscoveryService(),
                    List.of());
            ProjectInspectionService.ProjectView view = inspection.view(project);

            assertEquals("READY", view.indexState());
            assertEquals("snapshot-n-plus-1", view.activeSnapshotId());
            assertEquals("snapshot-n-plus-1", backend.indexStateStore().findProjectState(project.id())
                    .orElseThrow().activeSnapshotId().orElseThrow());
            assertEquals("snapshot-n-plus-1", backend.snapshotStore().loadActiveKnowledge(project.id())
                    .orElseThrow().snapshotId());
        }
    }
}
