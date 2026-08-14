package com.minos.storage.postgresql;

import com.minos.orchestration.IndexingLifecycleService;
import com.minos.orchestration.IndexingRuntimePorts.ActiveSnapshotObservation;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.ProjectIndexState;
import com.minos.registry.RegisteredProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PostgresAuthoritativeSnapshotConsistencyTest extends PostgresTestSupport {

    @Test
    void projectStateRejectsPostgresqlReadyMetadataWhenAuthorityHasNoSnapshot(@TempDir Path temp) throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));
        Path projectRoot = Files.createDirectories(temp.resolve("project"));
        RegisteredProject project = new PostgresProjectRegistry(connections, home)
                .registerProject(projectRoot, "ghost-project");
        PostgresIndexStateStore states = new PostgresIndexStateStore(connections, new PostgresJsonCodec());
        states.saveProjectState(new ProjectIndexState(
                project.id(),
                ProjectIndexState.Availability.READY,
                Optional.of("snapshot-ghost"),
                Optional.empty(),
                Instant.parse("2026-08-14T08:00:00Z"),
                Optional.of("persisted PostgreSQL metadata")));
        SnapshotPromoter emptyAuthority = new SnapshotPromoter() {
            @Override
            public void promote(UUID projectId, UUID runId, String stagedSnapshotId) { }

            @Override
            public ActiveSnapshotObservation observeActiveSnapshot(UUID projectId) {
                return ActiveSnapshotObservation.noActiveSnapshot();
            }
        };
        IndexingLifecycleService lifecycle = new IndexingLifecycleService(
                List.of(),
                request -> { throw new AssertionError("staging is not part of projectState consistency"); },
                emptyAuthority,
                states);

        assertThrows(IllegalStateException.class, () -> lifecycle.projectState(project.id()));
        assertEquals(Optional.of("snapshot-ghost"),
                states.findProjectState(project.id()).orElseThrow().activeSnapshotId());
    }
}
