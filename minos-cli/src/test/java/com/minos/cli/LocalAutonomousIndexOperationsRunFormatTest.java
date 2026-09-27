package com.minos.cli;

import com.minos.application.MinosApplication;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.IndexingRun.ExecutionCheckpoint;
import com.minos.orchestration.IndexingRun.IndexerExecution;
import com.minos.orchestration.ProjectIndexState;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R1 (V6) : la récupération CLI d'un run promu conserve le format du run et ses points de contrôle. */
class LocalAutonomousIndexOperationsRunFormatTest {

    @Test
    void recoveringAPromotedRunKeepsItsFormatVersionAndCheckpoints(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Path projectRoot = Files.createDirectories(temp.resolve("project"));
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");

        try (MinosApplication application = MinosApplication.open(home)) {
            RegisteredProject project = application.projectRegistry().registerProject(projectRoot, "recovered");
            CodeKnowledgeSnapshotStore snapshots = application.snapshotStore();
            CodeKnowledgeSnapshot snapshot = snapshots.publish(
                    project.id(), "snapshot-promoted", List.of(), List.of(), List.of());
            IndexingRun failed = new IndexingRun(
                    UUID.randomUUID(), project.id(), IndexingRun.Status.FAILED, IndexingRun.Phase.PROMOTION,
                    Instant.parse("2026-09-26T10:00:00Z"), Optional.of(Instant.parse("2026-09-26T10:01:00Z")),
                    List.of(new IndexerExecution(Language.JAVA, "scip-java",
                            temp.resolve("index.scip").toAbsolutePath().normalize(), Optional.of(
                            new ExecutionCheckpoint(Path.of(""), "0.10.0", 12L, "a".repeat(64), "b".repeat(64),
                                    IndexingMode.FULL, List.of(), Instant.parse("2026-09-26T10:00:30Z"))))),
                    Optional.of(snapshot.snapshotId()), Optional.empty(), Optional.empty(),
                    Optional.of("metadata finalization failed"), IndexingRun.CURRENT_FORMAT_VERSION);
            application.indexStateStore().saveRun(failed);
            application.indexStateStore().saveProjectState(new ProjectIndexState(project.id(),
                    ProjectIndexState.Availability.FAILED, Optional.empty(), Optional.of(failed.id()),
                    Instant.parse("2026-09-26T10:01:00Z"), Optional.of("crashed after promotion")));

            try (LocalAutonomousIndexOperations operations = new LocalAutonomousIndexOperations(application)) {
                Method recover = LocalAutonomousIndexOperations.class
                        .getDeclaredMethod("recoverPromotedRunIfNeeded", IndexingRun.class);
                recover.setAccessible(true);
                IndexingRun recovered = (IndexingRun) recover.invoke(operations, failed);

                assertEquals(IndexingRun.Status.SUCCEEDED, recovered.status());
                assertEquals(IndexingRun.CURRENT_FORMAT_VERSION, recovered.runFormatVersion(),
                        "recovery must not silently downgrade a current-format run to the legacy format");
                assertTrue(recovered.executions().getFirst().checkpoint().isPresent());
                IndexingRun persisted = application.indexStateStore().findRun(failed.id()).orElseThrow();
                assertEquals(IndexingRun.CURRENT_FORMAT_VERSION, persisted.runFormatVersion());
            }
        }
    }
}
