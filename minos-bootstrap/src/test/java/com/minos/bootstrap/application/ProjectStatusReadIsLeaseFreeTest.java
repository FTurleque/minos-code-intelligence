package com.minos.bootstrap.application;

import com.minos.application.ProjectInspectionService;
import com.minos.discovery.ProjectDiscoveryService;
import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.ProjectIndexState;
import com.minos.registry.RegisteredProject;
import com.minos.storage.local.orchestration.FileIndexStateStore;
import com.minos.storage.local.registry.LocalProjectRegistry;
import com.minos.storage.local.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A status read ({@code minos_index_status}) takes no lifecycle lease and writes nothing (lot 2, P1).
 *
 * <p>The "indexing process" and the "reading process" are two {@link FileIndexStateStore} instances
 * over the same directory: they share no monitor and no thread-local lease depth, only the files and
 * the operating-system lock, exactly like two processes.</p>
 */
class ProjectStatusReadIsLeaseFreeTest {

    private static final Instant PUBLISHED_AT = Instant.parse("2026-09-30T08:00:00Z");

    @Test
    void statusAnswersInsteadOfFailingWhileAnotherProcessHoldsTheLifecycleLease(@TempDir Path root) throws Exception {
        Fixture fixture = new Fixture(root);
        // The run has promoted its snapshot and not yet published the matching state: the window in
        // which the old read had to take the lifecycle lease to repair the metadata.
        fixture.snapshots.publish(fixture.project.id(), "snapshot-new", List.of(), List.of(), List.of());
        fixture.indexerProcess.saveProjectState(fixture.state(
                ProjectIndexState.Availability.INDEXING, "snapshot-old", "indexing run in progress"));
        byte[] published = Files.readAllBytes(fixture.stateFile());

        try (IndexStateStore.ProjectLease ignored = fixture.indexerProcess.acquireProjectLease(fixture.project.id())) {
            ProjectInspectionService.ProjectView view = fixture.readOnAnotherThread();

            assertEquals("INDEXING", view.indexState(),
                    "the run is still in flight: the read reports the state its owner published");
            assertEquals("snapshot-new", view.activeSnapshotId(),
                    "the active snapshot is the authoritative one, possibly ahead of the published state");
        }
        assertArrayEquals(published, Files.readAllBytes(fixture.stateFile()),
                "a read never rewrites the state it reports");
    }

    @Test
    void statusBeforeThePromotionIsLockFreeToo(@TempDir Path root) throws Exception {
        Fixture fixture = new Fixture(root);
        fixture.snapshots.publish(fixture.project.id(), "snapshot-old", List.of(), List.of(), List.of());
        fixture.indexerProcess.saveProjectState(fixture.state(
                ProjectIndexState.Availability.INDEXING, "snapshot-old", "indexing run in progress"));

        try (IndexStateStore.ProjectLease ignored = fixture.indexerProcess.acquireProjectLease(fixture.project.id())) {
            ProjectInspectionService.ProjectView view = fixture.readOnAnotherThread();

            assertEquals("INDEXING", view.indexState());
            assertEquals("snapshot-old", view.activeSnapshotId());
        }
    }

    @Test
    void aReadNeverRepairsTheStateItReportsEvenWhenNoRunHoldsTheLease(@TempDir Path root) throws Exception {
        Fixture fixture = new Fixture(root);
        fixture.snapshots.publish(fixture.project.id(), "snapshot-new", List.of(), List.of(), List.of());
        fixture.indexerProcess.saveProjectState(fixture.state(
                ProjectIndexState.Availability.READY, "snapshot-old", "committed before the crash"));
        byte[] published = Files.readAllBytes(fixture.stateFile());

        ProjectInspectionService.ProjectView view = fixture.readOnAnotherThread();

        assertEquals("READY", view.indexState());
        assertEquals("snapshot-new", view.activeSnapshotId(),
                "the answer is what the repair would publish, computed in memory");
        assertArrayEquals(published, Files.readAllBytes(fixture.stateFile()),
                "the repair is left to the next indexing run, which owns the lease");
        try (var entries = Files.list(fixture.stateFile().getParent())) {
            assertFalse(entries.anyMatch(entry -> entry.getFileName().toString().startsWith(".state-")),
                    "a read leaves no temporary file behind");
        }
    }

    private static final class Fixture {
        private final RegisteredProject project;
        private final FileIndexStateStore indexerProcess;
        private final FileIndexStateStore readerProcess;
        private final FileSymbolSnapshotStore snapshots;
        private final ProjectInspectionService inspection;
        private final Path stateRoot;

        private Fixture(Path root) throws Exception {
            Path home = Files.createDirectories(root.resolve("home"));
            Path projectRoot = Files.createDirectories(root.resolve("project"));
            LocalProjectRegistry registry = new LocalProjectRegistry(home.resolve("registry"));
            this.project = registry.registerProject(projectRoot, "status-project");
            this.stateRoot = home.resolve("index-state");
            this.indexerProcess = new FileIndexStateStore(stateRoot);
            this.readerProcess = new FileIndexStateStore(stateRoot);
            this.snapshots = new FileSymbolSnapshotStore(home.resolve("symbol-snapshots"));
            this.inspection = new ProjectInspectionService(
                    home, registry, snapshots, readerProcess, new ProjectDiscoveryService(), List.of());
        }

        private Path stateFile() {
            return stateRoot.resolve("projects").resolve(project.id() + ".properties");
        }

        private ProjectIndexState state(ProjectIndexState.Availability availability, String snapshotId, String detail) {
            return new ProjectIndexState(project.id(), availability, Optional.of(snapshotId),
                    Optional.of(UUID.randomUUID()), PUBLISHED_AT, Optional.of(detail));
        }

        /** The read runs on its own thread: the lease is owned by the thread that holds it, like a process. */
        private ProjectInspectionService.ProjectView readOnAnotherThread() throws Exception {
            ExecutorService reader = Executors.newSingleThreadExecutor();
            try {
                Future<ProjectInspectionService.ProjectView> answer =
                        reader.submit(() -> inspection.inspectProject(project.id().toString()));
                return answer.get();
            } finally {
                reader.shutdownNow();
            }
        }
    }
}
