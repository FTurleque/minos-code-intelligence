package com.minos.cli;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.FileIndexStateStore;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.ProjectIndexState;
import com.minos.registry.LocalProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalProjectOperationsM14StateTest {

    @TempDir
    Path temporary;

    @Test
    void indexStatusUsesPersistentLifecycleStateInsteadOfAssumingReadyFromActiveSnapshot() throws Exception {
        Path home = temporary.resolve("home");
        Path projectRoot = temporary.resolve("project");
        Files.createDirectories(projectRoot);

        LocalProjectRegistry registry = new LocalProjectRegistry(home.resolve("registry"));
        RegisteredProject project = registry.registerProject(projectRoot, "demo");
        FileSymbolSnapshotStore snapshots = new FileSymbolSnapshotStore(home.resolve("symbol-snapshots"));
        snapshots.publish(project.id(), "snapshot-1", List.of(), List.of(), List.of());

        UUID successfulRunId = UUID.randomUUID();
        Instant successAt = Instant.parse("2026-07-24T10:00:00Z");
        FileIndexStateStore stateStore = new FileIndexStateStore(home.resolve("index-state"));
        stateStore.saveRun(new IndexingRun(
                successfulRunId,
                project.id(),
                IndexingRun.Status.SUCCEEDED,
                IndexingRun.Phase.COMPLETED,
                successAt.minusSeconds(30),
                Optional.of(successAt),
                List.of(new IndexingRun.IndexerExecution(
                        Language.JAVA,
                        "scip-java",
                        home.resolve("runs").resolve(successfulRunId.toString()).resolve("scip-java/index.scip")
                )),
                Optional.of("snapshot-1"),
                Optional.empty(),
                Optional.of("snapshot-1"),
                Optional.of("ok")
        ));
        stateStore.saveProjectState(new ProjectIndexState(
                project.id(),
                ProjectIndexState.Availability.STALE,
                Optional.of("snapshot-1"),
                Optional.of(UUID.randomUUID()),
                successAt.plusSeconds(30),
                Optional.of("latest refresh failed")
        ));

        ProjectOperations.ProjectView view = new LocalProjectOperations(home).inspectProject("demo");

        assertEquals("STALE", view.indexState());
        assertEquals("snapshot-1", view.activeSnapshotId());
        assertEquals(successAt.toString(), view.lastSuccessfulIndexAt());
        assertEquals("scip-java", view.providerId());
        assertEquals("scip-java@0.12.3", view.providerVersion());
    }
}
