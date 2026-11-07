package com.minos.application;

import com.minos.application.indexing.ProjectIndexingUseCase;
import com.minos.dynamic.RuntimeObservationStore;
import com.minos.git.GitIntelligence;
import com.minos.incremental.ProjectFingerprintSnapshotStore;
import com.minos.orchestration.InMemoryIndexStateStore;
import com.minos.orchestration.IndexerProviderCatalog;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotStager;
import com.minos.orchestration.ResumableRunMarkers;
import com.minos.program.analysis.ProgramGraphService;
import com.minos.registry.ProjectRegistry;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.semantic.SemanticVectorStore;
import com.minos.storage.StorageBackend;
import com.minos.storage.StorageRetentionService;
import com.minos.store.CodeKnowledgeSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The WSL2 route is offered only by a composition root that ships it, and then reuses the single orchestration. */
class WslIndexingRouteWiringTest {

    @TempDir Path home;

    @Test
    void aCompositionRootOffersNoRouteByDefault() throws Exception {
        try (MinosApplication application = application(null)) {
            assertEquals(Optional.empty(), application.compositionRoot().wslIndexingRoute(home));
            assertEquals(Optional.empty(), ProjectIndexingUseCase.viaWsl(application, Optional.of("Ubuntu")));
            assertThrows(NullPointerException.class, () -> application.compositionRoot().wslIndexingRoute(null));
        }
    }

    @Test
    void anOfferedRouteReceivesTheDistributionAndYieldsTheSingleUseCase() throws Exception {
        List<Optional<String>> requested = new ArrayList<>();
        WslIndexingRoute route = distribution -> {
            requested.add(distribution);
            return UnaryOperator.<IndexerExecutor>identity();
        };
        try (MinosApplication application = application(route)) {
            assertTrue(ProjectIndexingUseCase.viaWsl(application, Optional.of("Ubuntu-24.04")).isPresent());
            assertTrue(ProjectIndexingUseCase.viaWsl(application, Optional.empty()).isPresent());
            assertEquals(List.of(Optional.of("Ubuntu-24.04"), Optional.empty()), requested);
            assertThrows(NullPointerException.class, () -> ProjectIndexingUseCase.viaWsl(application, null));
        }
    }

    /** An application whose composition root answers only what the indexing use case wiring asks for. */
    private MinosApplication application(WslIndexingRoute route) throws Exception {
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
                .programGraphProviders(ProgramGraphService.productionProviders())
                .scipArtifactImporter((indexFile, request, snapshots) -> {
                    throw new UnsupportedOperationException("import");
                });
        builder.composer = composer(route);
        return builder.build();
    }

    private static MinosApplicationComposer composer(WslIndexingRoute route) {
        InvocationHandler handler = (self, method, arguments) -> switch (method.getName()) {
            case "wslIndexingRoute" -> route == null
                    ? InvocationHandler.invokeDefault(self, method, arguments)
                    : Optional.of(route);
            case "resumableRunMarkers" -> unused(ResumableRunMarkers.class);
            case "toString" -> "composer";
            case "hashCode" -> System.identityHashCode(self);
            case "equals" -> self == arguments[0];
            default -> throw new UnsupportedOperationException("composer." + method.getName());
        };
        return (MinosApplicationComposer) Proxy.newProxyInstance(MinosApplicationComposer.class.getClassLoader(),
                new Class<?>[]{MinosApplicationComposer.class}, handler);
    }

    private static <T> T unused(Class<T> type) {
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, arguments) ->
                switch (method.getName()) {
                    case "toString" -> type.getSimpleName();
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == arguments[0];
                    case "id" -> "in-memory";
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(type.getSimpleName() + "." + method.getName());
                });
        return type.cast(proxy);
    }
}
