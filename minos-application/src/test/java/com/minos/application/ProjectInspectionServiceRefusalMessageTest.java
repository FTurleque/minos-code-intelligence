package com.minos.application;

import com.minos.discovery.ProjectDiscoveryService;
import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.ProjectIndexState;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A CLI index history without a required property is refused by its file name, never by its absolute path. */
class ProjectInspectionServiceRefusalMessageTest {

    private static final Instant AT = Instant.parse("2026-10-24T08:00:00Z");

    @TempDir
    Path temp;

    @Test
    void aHistoryWithoutASnapshotIdIsRefusedByItsFileName() throws Exception {
        UUID projectId = UUID.randomUUID();
        Path home = temp.resolve("home");
        Path history = Files.createDirectories(home.resolve("cli-index-history"));
        Files.writeString(history.resolve(projectId + ".properties"),
                "providerId=scip-java\ncompletedAt=" + AT + "\n", StandardCharsets.ISO_8859_1);
        Path root = Files.createDirectories(temp.resolve("project"));
        ProjectInspectionService service = new ProjectInspectionService(home, unusedRegistry(),
                new ActiveSnapshotReadCostTest.CountingSnapshotStore(
                        new CodeKnowledgeSnapshot(projectId, "snapshot", List.of(), List.of(), List.of())),
                new EmptyStateStore(), new ProjectDiscoveryService(), List.of());
        RegisteredProject project = new RegisteredProject(projectId, root, "Project", Optional.empty(), AT, AT);

        IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> service.statusView(project));

        assertEquals("missing index property 'snapshotId' in " + projectId + ".properties", refusal.getMessage());
        assertFalse(refusal.getMessage().contains(temp.toString()), refusal.getMessage());
    }

    private static ProjectRegistry unusedRegistry() {
        return (ProjectRegistry) Proxy.newProxyInstance(ProjectRegistry.class.getClassLoader(),
                new Class<?>[] {ProjectRegistry.class}, (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A state store that knows no project and refuses every write. */
    private static final class EmptyStateStore implements IndexStateStore {
        @Override public Optional<ProjectIndexState> findProjectState(UUID projectId) { return Optional.empty(); }
        @Override public Optional<IndexingRun> findRun(UUID runId) { return Optional.empty(); }
        @Override public List<IndexingRun> listRuns(UUID projectId) { return List.of(); }
        @Override public void saveProjectState(ProjectIndexState state) { throw new AssertionError("no write"); }
        @Override public void saveRun(IndexingRun run) { throw new AssertionError("no write"); }
    }
}
