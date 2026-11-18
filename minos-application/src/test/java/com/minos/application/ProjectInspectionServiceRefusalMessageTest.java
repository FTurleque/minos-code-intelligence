package com.minos.application;

import com.minos.discovery.ProjectDiscoveryService;
import com.minos.orchestration.IndexImportHistory;
import com.minos.orchestration.IndexImportHistoryStore;
import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.ProjectIndexState;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The inspection reads the import history through its port: the refusal of a damaged history is relayed as the
 * port words it, with the file leaf and no absolute path, and a readable history fills the view.
 */
class ProjectInspectionServiceRefusalMessageTest {

    private static final Instant AT = Instant.parse("2026-10-24T08:00:00Z");

    @TempDir
    Path temp;

    @Test
    void theRefusalOfTheHistoryPortIsRelayedWithoutAPath() throws Exception {
        UUID projectId = UUID.randomUUID();
        IllegalStateException portRefusal =
                new IllegalStateException("missing index property 'snapshotId' in " + projectId + ".properties");
        ProjectInspectionService service = service(projectId, ImportHistoryStores.failing(portRefusal));
        RegisteredProject project = project(projectId);

        IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> service.statusView(project));

        assertEquals("missing index property 'snapshotId' in " + projectId + ".properties", refusal.getMessage());
        assertFalse(refusal.getMessage().contains(temp.toString()), refusal.getMessage());
    }

    @Test
    void aHistoryOfTheActiveSnapshotFillsTheProviderVersionAndDate() throws Exception {
        UUID projectId = UUID.randomUUID();
        IndexImportHistory history = new IndexImportHistory("snapshot", "scip-java", "0.13.1", AT);
        IndexImportHistoryStore store = new IndexImportHistoryStore() {
            @Override public Optional<IndexImportHistory> read(UUID id) {
                return id.equals(projectId) ? Optional.of(history) : Optional.empty();
            }

            @Override public void write(UUID id, IndexImportHistory value) {
                throw new AssertionError("no write");
            }
        };

        ProjectInspectionService.ProjectView view = service(projectId, store).statusView(project(projectId));

        assertEquals("scip-java", view.providerId());
        assertEquals("0.13.1", view.providerVersion());
        assertEquals(AT.toString(), view.lastSuccessfulIndexAt());
    }

    @Test
    void aMissingHistoryPortIsRefusedByName() {
        ProjectRegistry registry = unusedRegistry();
        ActiveSnapshotReadCostTest.CountingSnapshotStore snapshots = snapshots(UUID.randomUUID());
        EmptyStateStore states = new EmptyStateStore();
        ProjectDiscoveryService discovery = new ProjectDiscoveryService();

        NullPointerException refusal = assertThrows(NullPointerException.class,
                () -> new ProjectInspectionService(null, registry, snapshots, states, discovery, List.of()));

        assertEquals("historyStore", refusal.getMessage());
    }

    private ProjectInspectionService service(UUID projectId, IndexImportHistoryStore store) {
        return new ProjectInspectionService(store, unusedRegistry(), snapshots(projectId), new EmptyStateStore(),
                new ProjectDiscoveryService(), List.of());
    }

    private RegisteredProject project(UUID projectId) throws Exception {
        Path root = Files.createDirectories(temp.resolve("project"));
        return new RegisteredProject(projectId, root, "Project", Optional.empty(), AT, AT);
    }

    private static ActiveSnapshotReadCostTest.CountingSnapshotStore snapshots(UUID projectId) {
        return new ActiveSnapshotReadCostTest.CountingSnapshotStore(
                new CodeKnowledgeSnapshot(projectId, "snapshot", List.of(), List.of(), List.of()));
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
