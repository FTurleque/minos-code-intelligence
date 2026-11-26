package com.minos.application;

import com.minos.application.indexing.ProjectIndexingUseCase;
import com.minos.dynamic.RuntimeObservationStore;
import com.minos.git.GitIntelligence;
import com.minos.incremental.ProjectFingerprintSnapshotStore;
import com.minos.orchestration.InMemoryIndexStateStore;
import com.minos.orchestration.IndexerProviderCatalog;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotStager;
import com.minos.orchestration.ResumableRunMarkers;
import com.minos.program.analysis.TestProgramGraphProviders;
import com.minos.registry.ProjectRegistry;
import com.minos.remote.RemoteIndexingRuntime;
import com.minos.remote.RemoteRepositoryMaterializer;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.semantic.SemanticVectorStore;
import com.minos.storage.StorageBackend;
import com.minos.storage.StorageRetentionService;
import com.minos.store.CodeKnowledgeSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The facade exposes the use cases, built on first request from the collaborators of the application (ADR 0087):
 * nothing is asked of the composition root at opening, a failed construction is retried by the next request.
 */
class UseCaseFacadeTest {

    @TempDir
    Path home;

    private final AtomicInteger materializers = new AtomicInteger();
    private final AtomicInteger runtimes = new AtomicInteger();
    private final AtomicInteger markers = new AtomicInteger();

    @Test
    void openingAsksNoUseCaseCollaboratorOfTheCompositionRoot() throws Exception {
        try (MinosApplication application = application(0)) {
            assertEquals(0, materializers.get());
            assertEquals(0, runtimes.get());
            assertEquals(0, markers.get());
        }
    }

    @Test
    void theLocalIndexingAndTheExportAreBuiltOnceOnFirstRequest() throws Exception {
        try (MinosApplication application = application(0)) {
            ProjectIndexingUseCase indexing = application.projectIndexing();

            assertSame(indexing, application.projectIndexing());
            assertEquals(1, markers.get());
            ProjectIndexingUseCase decorated = MinosApplicationTestAccess.decoratedProjectIndexing(application,
                    UnaryOperator.identity());
            assertNotSame(indexing, decorated);
            assertNotSame(decorated, MinosApplicationTestAccess.decoratedProjectIndexing(application,
                    UnaryOperator.identity()));
            assertSame(application.knowledgeExport(), application.knowledgeExport());
        }
    }

    @Test
    void aFailingMaterializerIsPropagatedAndTheNextRequestTriesAgain() throws Exception {
        try (MinosApplication application = application(1)) {
            IOException failure = assertThrows(IOException.class, application::remoteIndexing);
            assertEquals("materializer unavailable", failure.getMessage());
            assertEquals(0, runtimes.get());

            assertSame(application.remoteIndexing(), application.remoteIndexing());
            assertEquals(2, materializers.get());
            assertEquals(1, runtimes.get());
        }
    }

    @Test
    void noWslRouteYieldsEmptyWithoutResolvingAnything() throws Exception {
        try (MinosApplication application = application(0)) {
            assertEquals(Optional.empty(), application.projectIndexingViaWsl(Optional.of("Ubuntu")));
            assertEquals(0, markers.get());
            assertThrows(NullPointerException.class, () -> application.projectIndexingViaWsl(null));
        }
    }

    private MinosApplication application(int failingMaterializers) throws Exception {
        AtomicInteger failures = new AtomicInteger(failingMaterializers);
        MinosApplication.Builder builder = MinosApplication.builder(home)
                .storageBackend(unused(StorageBackend.class))
                .projectRegistry(unused(ProjectRegistry.class))
                .snapshotStore(unused(CodeKnowledgeSnapshotStore.class))
                .indexStateStore(new InMemoryIndexStateStore())
                .fingerprintStore(unused(ProjectFingerprintSnapshotStore.class))
                .semanticVectorStore(unused(SemanticVectorStore.class))
                .runtimeObservationStore(unused(RuntimeObservationStore.class))
                .retentionService(unused(StorageRetentionService.class))
                .indexerDescriptors(List.of())
                .providerCatalog(unused(IndexerProviderCatalog.class))
                .providerRuntimeManager(unused(ProviderRuntimeManager.class))
                .snapshotLifecycle(unused(SnapshotStager.class), unused(SnapshotPromoter.class))
                .gitIntelligence(unused(GitIntelligence.class))
                .programGraphProviders(TestProgramGraphProviders.production())
                .scipArtifactImporter((indexFile, request, snapshots) -> {
                    throw new UnsupportedOperationException("import");
                });
        builder.composer = (MinosApplicationComposer) Proxy.newProxyInstance(
                MinosApplicationComposer.class.getClassLoader(), new Class<?>[] {MinosApplicationComposer.class},
                (self, method, arguments) -> switch (method.getName()) {
                    case "indexImportHistoryStore" -> ImportHistoryStores.none();
                    case "completeProgramGraphProviders" -> TestProgramGraphProviders.completedArgument(arguments[0]);
                    case "resumableRunMarkers" -> {
                        markers.incrementAndGet();
                        yield unused(ResumableRunMarkers.class);
                    }
                    case "remoteRepositoryMaterializer" -> {
                        materializers.incrementAndGet();
                        if (failures.getAndDecrement() > 0) throw new IOException("materializer unavailable");
                        yield unused(RemoteRepositoryMaterializer.class);
                    }
                    case "remoteIndexingRuntime" -> {
                        runtimes.incrementAndGet();
                        yield unused(RemoteIndexingRuntime.class);
                    }
                    case "wslIndexingRoute" -> Optional.empty();
                    case "toString" -> "composer";
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == arguments[0];
                    default -> throw new UnsupportedOperationException("composer." + method.getName());
                });
        return builder.build();
    }

    private static <T> T unused(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (self, method, arguments) -> switch (method.getName()) {
                    case "toString" -> type.getSimpleName();
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == arguments[0];
                    case "id" -> "in-memory";
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(type.getSimpleName() + "." + method.getName());
                }));
    }
}
