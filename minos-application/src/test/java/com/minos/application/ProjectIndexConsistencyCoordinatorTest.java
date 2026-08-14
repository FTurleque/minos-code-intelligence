package com.minos.application;

import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.ProjectIndexState;
import com.minos.registry.RegisteredProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectIndexConsistencyCoordinatorTest {

    private static final Instant T0 = Instant.parse("2026-08-14T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0.plusSeconds(60), ZoneOffset.UTC);

    @Test
    void inspectionAfterRestartReconcilesFileBackendFromAuthoritativeSnapshot(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Path projectRoot = temp.resolve("project");
        Files.createDirectories(projectRoot);
        UUID projectId;

        try (MinosApplication application = MinosApplication.open(home)) {
            RegisteredProject project = application.projectRegistry().registerProject(projectRoot, "project");
            projectId = project.id();
            application.snapshotStore().publish(projectId, "snapshot-n", List.of(), List.of(), List.of());
            application.indexStateStore().saveProjectState(ready(projectId, "snapshot-n", T0));

            application.snapshotStore().publish(projectId, "snapshot-n-plus-1", List.of(), List.of(), List.of());
            assertEquals("snapshot-n", application.indexStateStore().findProjectState(projectId)
                    .orElseThrow().activeSnapshotId().orElseThrow());
            assertEquals("snapshot-n-plus-1", application.snapshotStore().loadActiveKnowledge(projectId)
                    .orElseThrow().snapshotId());
        }

        try (MinosApplication restarted = MinosApplication.open(home)) {
            RegisteredProject project = restarted.projectRegistry().findProject(projectId).orElseThrow();
            ProjectInspectionService.ProjectView view = restarted.projectInspectionService().view(project);

            assertEquals("READY", view.indexState());
            assertEquals("snapshot-n-plus-1", view.activeSnapshotId());
            ProjectIndexState persisted = restarted.indexStateStore().findProjectState(projectId).orElseThrow();
            assertEquals("snapshot-n-plus-1", persisted.activeSnapshotId().orElseThrow());
            assertEquals("snapshot-n-plus-1", restarted.snapshotStore().loadActiveKnowledge(projectId)
                    .orElseThrow().snapshotId());
        }
    }

    @Test
    void firstStateRepairFailureIsRetriedAndCannotRemainVisible(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Path projectRoot = temp.resolve("project");
        Files.createDirectories(projectRoot);

        try (MinosApplication application = MinosApplication.open(home)) {
            RegisteredProject project = application.projectRegistry().registerProject(projectRoot, "project");
            UUID projectId = project.id();
            application.snapshotStore().publish(projectId, "snapshot-n", List.of(), List.of(), List.of());
            application.indexStateStore().saveProjectState(ready(projectId, "snapshot-n", T0));
            application.snapshotStore().publish(projectId, "snapshot-n-plus-1", List.of(), List.of(), List.of());

            AtomicInteger saves = new AtomicInteger();
            IndexStateStore flaky = new DelegatingIndexStateStore(application.indexStateStore()) {
                @Override
                public void saveProjectState(ProjectIndexState state) {
                    if (saves.getAndIncrement() == 0) throw new IllegalStateException("injected first save failure");
                    super.saveProjectState(state);
                }
            };
            ProjectIndexConsistencyCoordinator coordinator = new ProjectIndexConsistencyCoordinator(
                    application.snapshotStore(), flaky, CLOCK);

            ProjectIndexConsistencyCoordinator.Resolution resolution = coordinator.resolve(projectId);

            assertTrue(resolution.reconciled());
            assertEquals(2, saves.get());
            assertEquals("snapshot-n-plus-1", resolution.activeSnapshot().orElseThrow().snapshotId());
            assertEquals("snapshot-n-plus-1", resolution.indexState().activeSnapshotId().orElseThrow());
            assertEquals("snapshot-n-plus-1", application.indexStateStore().findProjectState(projectId)
                    .orElseThrow().activeSnapshotId().orElseThrow());
        }
    }

    @Test
    void allStateRepairAttemptsFailClosedWithoutLyingAboutActiveSnapshot(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Path projectRoot = temp.resolve("project");
        Files.createDirectories(projectRoot);

        try (MinosApplication application = MinosApplication.open(home)) {
            RegisteredProject project = application.projectRegistry().registerProject(projectRoot, "project");
            UUID projectId = project.id();
            application.snapshotStore().publish(projectId, "snapshot-n", List.of(), List.of(), List.of());
            application.indexStateStore().saveProjectState(ready(projectId, "snapshot-n", T0));
            application.snapshotStore().publish(projectId, "snapshot-n-plus-1", List.of(), List.of(), List.of());

            AtomicInteger saves = new AtomicInteger();
            IndexStateStore failing = new DelegatingIndexStateStore(application.indexStateStore()) {
                @Override
                public void saveProjectState(ProjectIndexState state) {
                    saves.incrementAndGet();
                    throw new IllegalStateException("injected persistent save failure");
                }
            };
            ProjectIndexConsistencyCoordinator coordinator = new ProjectIndexConsistencyCoordinator(
                    application.snapshotStore(), failing, CLOCK);

            IOException failure = assertThrows(IOException.class, () -> coordinator.resolve(projectId));

            assertTrue(failure.getMessage().contains("reconciliation"));
            assertEquals(2, saves.get());
            assertEquals("snapshot-n-plus-1", application.snapshotStore().loadActiveKnowledge(projectId)
                    .orElseThrow().snapshotId());
            assertEquals("snapshot-n", application.indexStateStore().findProjectState(projectId)
                    .orElseThrow().activeSnapshotId().orElseThrow());
        }
    }

    @Test
    void metadataCannotClaimAnActiveSnapshotWhenSnapshotStoreHasNone(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Path projectRoot = temp.resolve("project");
        Files.createDirectories(projectRoot);

        try (MinosApplication application = MinosApplication.open(home)) {
            RegisteredProject project = application.projectRegistry().registerProject(projectRoot, "project");
            application.indexStateStore().saveProjectState(ready(project.id(), "ghost", T0));
            ProjectIndexConsistencyCoordinator coordinator = new ProjectIndexConsistencyCoordinator(
                    application.snapshotStore(), application.indexStateStore(), CLOCK);

            IOException failure = assertThrows(IOException.class, () -> coordinator.resolve(project.id()));

            assertTrue(failure.getMessage().contains("SnapshotStore has no active snapshot"));
        }
    }

    private static ProjectIndexState ready(UUID projectId, String snapshotId, Instant at) {
        return new ProjectIndexState(
                projectId,
                ProjectIndexState.Availability.READY,
                Optional.of(snapshotId),
                Optional.empty(),
                at,
                Optional.of("test state"));
    }

    private static class DelegatingIndexStateStore implements IndexStateStore {
        private final IndexStateStore delegate;

        private DelegatingIndexStateStore(IndexStateStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<ProjectIndexState> findProjectState(UUID projectId) {
            return delegate.findProjectState(projectId);
        }

        @Override
        public Optional<IndexingRun> findRun(UUID runId) {
            return delegate.findRun(runId);
        }

        @Override
        public List<IndexingRun> listRuns(UUID projectId) {
            return delegate.listRuns(projectId);
        }

        @Override
        public void saveProjectState(ProjectIndexState state) {
            delegate.saveProjectState(state);
        }

        @Override
        public void saveRun(IndexingRun run) {
            delegate.saveRun(run);
        }
    }
}
