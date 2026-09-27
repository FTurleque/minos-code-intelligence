package com.minos.application;

import com.minos.discovery.ProjectDiscoveryService;
import com.minos.orchestration.FileIndexStateStore;
import com.minos.orchestration.ProjectIndexState;
import com.minos.registry.LocalProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectInspectionSnapshotConsistencyTest {

    @Test
    void inspectionNeverReportsReadyWhenPersistedSnapshotDoesNotExist(@TempDir Path root) throws Exception {
        Path home = Files.createDirectories(root.resolve("home"));
        Path projectRoot = Files.createDirectories(root.resolve("project"));
        LocalProjectRegistry registry = new LocalProjectRegistry(home.resolve("registry"));
        RegisteredProject project = registry.registerProject(projectRoot, "ghost-project");
        FileIndexStateStore states = new FileIndexStateStore(home.resolve("index-state"));
        states.saveProjectState(new ProjectIndexState(
                project.id(),
                ProjectIndexState.Availability.READY,
                Optional.of("snapshot-ghost"),
                Optional.empty(),
                Instant.parse("2026-08-14T08:00:00Z"),
                Optional.of("stale metadata")));
        FileSymbolSnapshotStore snapshots = new FileSymbolSnapshotStore(home.resolve("symbol-snapshots"));
        ProjectInspectionService inspection = new ProjectInspectionService(
                home,
                registry,
                snapshots,
                states,
                new ProjectDiscoveryService(),
                List.of());

        assertThrows(IOException.class, () -> inspection.inspectProject(project.id().toString()));
    }
}
