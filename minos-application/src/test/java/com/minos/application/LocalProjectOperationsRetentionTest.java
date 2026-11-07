package com.minos.application;

import com.minos.dynamic.RuntimeObservationStore;
import com.minos.git.GitIntelligence;
import com.minos.incremental.ProjectFingerprintSnapshotStore;
import com.minos.orchestration.InMemoryIndexStateStore;
import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.IndexStateStoreException;
import com.minos.orchestration.IndexerProviderCatalog;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotStager;
import com.minos.orchestration.ProjectIndexState;
import com.minos.orchestration.ScipSymbolSnapshotReport;
import com.minos.program.analysis.ProgramGraphService;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.semantic.SemanticVectorStore;
import com.minos.storage.StorageBackend;
import com.minos.storage.StorageRetentionService;
import com.minos.store.CodeKnowledgeSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * An explicit SCIP import applies the retention the indexing applies, once its snapshot is published, and a busy
 * retention leaves the import successful. The application is built from in-memory ports: no adapter, no composition
 * root.
 */
class LocalProjectOperationsRetentionTest {

    private static final String SKIPPED =
            "storage retention did not run (its lock was busy or unavailable); it runs after the next indexing";

    @TempDir Path temp;

    @Test
    void aPublishedImportCompactsTheProjectOnceAfterItsStateIsSaved() throws Exception {
        Fixture fixture = new Fixture(temp);

        ProjectOperations.IndexImportResult result = fixture.importScip("snapshot-1");

        assertEquals(ProjectOperations.IndexImportCommitStatus.COMMITTED, result.commitStatus());
        assertNull(result.diagnostic());
        assertEquals(List.of("publish snapshot-1", "state snapshot-1", "retention snapshot-1"), fixture.events);
    }

    @Test
    void aBusyRetentionLeavesTheImportCommittedWithADiagnosticWithoutItsCause() throws Exception {
        Fixture fixture = new Fixture(temp);
        fixture.retentionFailure = new IOException("storage retention lock busy at " + temp.toAbsolutePath());

        ProjectOperations.IndexImportResult result = fixture.importScip("snapshot-1");

        assertEquals(ProjectOperations.IndexImportCommitStatus.COMMITTED, result.commitStatus());
        assertEquals("snapshot-1", result.snapshotId());
        assertEquals(SKIPPED, result.diagnostic());
        assertFalse(result.diagnostic().contains(temp.toAbsolutePath().toString()));
        assertEquals(Optional.of("snapshot-1"), fixture.state.findProjectState(fixture.project.id())
                .flatMap(ProjectIndexState::activeSnapshotId));
    }

    @Test
    void aBusyRetentionAfterAPendingStateKeepsBothDiagnosticsInOrder() throws Exception {
        Fixture fixture = new Fixture(temp);
        fixture.state.failingSaves = 2;
        fixture.retentionFailure = new IOException("storage retention lock busy");

        ProjectOperations.IndexImportResult result = fixture.importScip("snapshot-1");

        assertEquals(ProjectOperations.IndexImportCommitStatus.COMMITTED_METADATA_PENDING, result.commitStatus());
        assertEquals("active snapshot committed; project index metadata persistence failed after 2 attempts: "
                + "state store unavailable; " + SKIPPED, result.diagnostic());
        assertEquals(List.of("publish snapshot-1", "retention none"), fixture.events);
    }

    @Test
    void aRefusedArtifactAppliesNoRetention() throws Exception {
        Fixture fixture = new Fixture(temp);

        String projectId = fixture.project.id().toString();
        Path missing = temp.resolve("missing.scip");
        try (LocalProjectOperations operations = new LocalProjectOperations(fixture.application())) {
            assertThrows(IllegalArgumentException.class,
                    () -> operations.importScip(projectId, missing, "scip-java", "1.0", null, "snapshot-1"));
        }

        assertEquals(List.of(), fixture.events);
    }

    @Test
    void aFailedPublicationAppliesNoRetention() throws Exception {
        Fixture fixture = new Fixture(temp);
        fixture.publicationFailure = new IOException("SCIP index could not be decoded");

        assertThrows(IOException.class, () -> fixture.importScip("snapshot-1"));

        assertEquals(List.of(), fixture.events);
    }

    /** In-memory ports around one registered project; every port the import does not use refuses to be called. */
    private static final class Fixture {
        final Path home;
        final Path artifact;
        final RegisteredProject project;
        final RecordingStateStore state;
        final List<String> events = new CopyOnWriteArrayList<>();
        IOException retentionFailure;
        IOException publicationFailure;

        Fixture(Path temp) throws IOException {
            this.home = temp.resolve("home");
            Path root = Files.createDirectories(temp.resolve("project"));
            this.artifact = Files.writeString(temp.resolve("index.scip"), "index");
            Instant now = Instant.parse("2026-11-07T10:00:00Z");
            this.project = new RegisteredProject(UUID.randomUUID(), root, "demo", Optional.empty(), now, now);
            this.state = new RecordingStateStore(events);
        }

        ProjectOperations.IndexImportResult importScip(String snapshotId) throws IOException {
            try (LocalProjectOperations operations = new LocalProjectOperations(application())) {
                return operations.importScip(project.id().toString(), artifact, "scip-java", "1.0", null, snapshotId);
            }
        }

        MinosApplication application() throws IOException {
            StorageRetentionService retention = (projectId, policy) -> {
                events.add("retention " + state.findProjectState(projectId)
                        .flatMap(ProjectIndexState::activeSnapshotId).orElse("none"));
                if (retentionFailure != null) throw retentionFailure;
                return new StorageRetentionService.RetentionResult(0, 0, 0);
            };
            MinosApplication.Builder builder = MinosApplication.builder(home)
                    .storageBackend(unused(StorageBackend.class))
                    .projectRegistry(registry())
                    .snapshotStore(unused(CodeKnowledgeSnapshotStore.class))
                    .indexStateStore(state)
                    .fingerprintStore(unused(ProjectFingerprintSnapshotStore.class))
                    .semanticVectorStore(unused(SemanticVectorStore.class))
                    .runtimeObservationStore(unused(RuntimeObservationStore.class))
                    .retentionService(retention)
                    .indexerDescriptors(List.of())
                    .providerCatalog(unused(IndexerProviderCatalog.class))
                    .providerRuntimeManager(unused(ProviderRuntimeManager.class))
                    .snapshotLifecycle(unused(SnapshotStager.class), unused(SnapshotPromoter.class))
                    .gitIntelligence(unused(GitIntelligence.class))
                    .programGraphProviders(ProgramGraphService.productionProviders())
                    .scipArtifactImporter((indexFile, request, snapshots) -> {
                        if (publicationFailure != null) throw publicationFailure;
                        events.add("publish " + request.snapshotId());
                        return new ScipSymbolSnapshotReport(request.snapshotId(),
                                1, 1, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
                    });
            // Every adapter is supplied above: the composition root is never asked for one.
            builder.composer = unused(MinosApplicationComposer.class);
            return builder.build();
        }

        private ProjectRegistry registry() {
            return port(ProjectRegistry.class, (name, arguments) -> switch (name) {
                case "findProject" -> project.id().equals(arguments[0]) ? Optional.of(project) : Optional.empty();
                case "listProjects" -> List.of(project);
                default -> throw new UnsupportedOperationException(name);
            });
        }
    }

    /** A state store that records each saved active snapshot and can fail its next saves. */
    private static final class RecordingStateStore implements IndexStateStore {
        private final InMemoryIndexStateStore delegate = new InMemoryIndexStateStore();
        private final List<String> events;
        int failingSaves;

        RecordingStateStore(List<String> events) {
            this.events = events;
        }

        @Override public Optional<ProjectIndexState> findProjectState(UUID projectId) {
            return delegate.findProjectState(projectId);
        }
        @Override public Optional<IndexingRun> findRun(UUID runId) { return delegate.findRun(runId); }
        @Override public List<IndexingRun> listRuns(UUID projectId) { return delegate.listRuns(projectId); }
        @Override public void saveRun(IndexingRun run) { delegate.saveRun(run); }
        @Override public ProjectLease acquireProjectLease(UUID projectId) {
            return delegate.acquireProjectLease(projectId);
        }

        @Override
        public void saveProjectState(ProjectIndexState state) {
            if (failingSaves > 0) {
                failingSaves--;
                throw new IndexStateStoreException("state store unavailable");
            }
            delegate.saveProjectState(state);
            events.add("state " + state.activeSnapshotId().orElse("none"));
        }
    }

    @FunctionalInterface
    private interface Handler {
        Object handle(String name, Object[] arguments);
    }

    private static <T> T unused(Class<T> type) {
        return port(type, (name, arguments) -> switch (name) {
            case "id" -> "in-memory";
            case "close" -> null;
            default -> throw new UnsupportedOperationException(type.getSimpleName() + "." + name);
        });
    }

    private static <T> T port(Class<T> type, Handler handler) {
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, arguments) ->
                switch (method.getName()) {
                    case "toString" -> type.getSimpleName();
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == arguments[0];
                    default -> handler.handle(method.getName(), arguments == null ? new Object[0] : arguments);
                });
        return type.cast(proxy);
    }
}
