package com.minos.cli;

import com.minos.application.MinosApplication;
import com.minos.orchestration.ProjectIndexState;
import com.minos.registry.RegisteredProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalAutonomousIndexOperationsStateTest {

    @Test
    void planFailsClosedWhenPersistedStateReferencesMissingAuthoritativeSnapshot(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Path projectRoot = Files.createDirectories(temp.resolve("project"));

        try (MinosApplication application = MinosApplication.open(home)) {
            RegisteredProject project = application.projectRegistry().registerProject(projectRoot, "ghost-project");
            ProjectIndexState ghostState = new ProjectIndexState(
                    project.id(),
                    ProjectIndexState.Availability.READY,
                    Optional.of("ghost-snapshot"),
                    Optional.empty(),
                    Instant.parse("2026-08-14T12:00:00Z"),
                    Optional.of("fixture persisted state"));
            application.indexStateStore().saveProjectState(ghostState);

            try (LocalAutonomousIndexOperations operations = new LocalAutonomousIndexOperations(application)) {
                IOException failure = assertThrows(
                        IOException.class,
                        () -> operations.plan(project.id().toString(), null, false));

                assertTrue(failure.getMessage().contains("snapshot store has none"));
                ProjectIndexState persisted = application.indexStateStore()
                        .findProjectState(project.id())
                        .orElseThrow();
                assertEquals(ProjectIndexState.Availability.READY, persisted.availability());
                assertEquals(Optional.of("ghost-snapshot"), persisted.activeSnapshotId(),
                        "fail-closed planning must preserve the corrupt evidence for diagnosis/recovery");
            }
        }
    }
}
