package com.minos.application;

import com.minos.dynamic.RuntimeObservationStore;
import com.minos.git.GitIntelligence;
import com.minos.incremental.ProjectFingerprintSnapshotStore;
import com.minos.orchestration.InMemoryIndexStateStore;
import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.IndexerProviderCatalog;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotStager;
import com.minos.orchestration.ResumableRunMarkers;
import com.minos.program.analysis.TestProgramGraphProviders;
import com.minos.registry.ProjectRegistry;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.semantic.SemanticVectorStore;
import com.minos.storage.StorageBackend;
import com.minos.storage.StorageRetentionService;
import com.minos.store.CodeKnowledgeSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The test access relays the package-private ports of the facade without copy (AUD-ARC-02). */
class MinosApplicationTestAccessTest {

    private static final Set<String> RELAYED = Set.of("compositionRoot", "projectRegistry", "snapshotStore",
            "indexStateStore", "fingerprintStore", "runtimeObservationStore", "retentionService", "discoveryService",
            "fingerprintService", "providerRuntimeManager", "indexerDescriptors", "snapshotStager", "snapshotPromoter");

    @TempDir Path home;

    @Test
    void everyRelayReturnsTheInstanceTheApplicationWasBuiltWith() throws Exception {
        ProjectRegistry registry = unused(ProjectRegistry.class);
        CodeKnowledgeSnapshotStore snapshots = unused(CodeKnowledgeSnapshotStore.class);
        IndexStateStore states = new InMemoryIndexStateStore();
        ProjectFingerprintSnapshotStore fingerprints = unused(ProjectFingerprintSnapshotStore.class);
        RuntimeObservationStore observations = unused(RuntimeObservationStore.class);
        StorageRetentionService retention = unused(StorageRetentionService.class);
        ProviderRuntimeManager runtimes = unused(ProviderRuntimeManager.class);
        SnapshotStager stager = unused(SnapshotStager.class);
        SnapshotPromoter promoter = unused(SnapshotPromoter.class);
        MinosApplicationComposer composer = composer();
        MinosApplication.Builder builder = MinosApplication.builder(home)
                .storageBackend(unused(StorageBackend.class))
                .projectRegistry(registry)
                .snapshotStore(snapshots)
                .indexStateStore(states)
                .fingerprintStore(fingerprints)
                .semanticVectorStore(unused(SemanticVectorStore.class))
                .runtimeObservationStore(observations)
                .retentionService(retention)
                .indexerDescriptors(List.of())
                .providerCatalog(unused(IndexerProviderCatalog.class))
                .providerRuntimeManager(runtimes)
                .snapshotLifecycle(stager, promoter)
                .gitIntelligence(unused(GitIntelligence.class))
                .programGraphProviders(TestProgramGraphProviders.production())
                .scipArtifactImporter((indexFile, request, store) -> {
                    throw new UnsupportedOperationException("import");
                });
        builder.composer = composer;
        try (MinosApplication application = builder.build()) {
            assertSame(composer, MinosApplicationTestAccess.compositionRoot(application));
            assertSame(registry, MinosApplicationTestAccess.projectRegistry(application));
            assertSame(snapshots, MinosApplicationTestAccess.snapshotStore(application));
            assertSame(states, MinosApplicationTestAccess.indexStateStore(application));
            assertSame(fingerprints, MinosApplicationTestAccess.fingerprintStore(application));
            assertSame(observations, MinosApplicationTestAccess.runtimeObservationStore(application));
            assertSame(retention, MinosApplicationTestAccess.retentionService(application));
            assertSame(runtimes, MinosApplicationTestAccess.providerRuntimeManager(application));
            assertSame(stager, MinosApplicationTestAccess.snapshotStager(application));
            assertSame(promoter, MinosApplicationTestAccess.snapshotPromoter(application));
            assertEquals(List.of(), MinosApplicationTestAccess.indexerDescriptors(application));
            assertNotNull(MinosApplicationTestAccess.discoveryService(application));
            assertSame(application.discoveryService(), MinosApplicationTestAccess.discoveryService(application));
            assertNotNull(MinosApplicationTestAccess.fingerprintService(application));
            assertSame(application.fingerprintService(), MinosApplicationTestAccess.fingerprintService(application));
        }
    }

    @Test
    void theFacadeKeepsEveryRelayedPortPackagePrivate() {
        Set<String> publicMethods = Arrays.stream(MinosApplication.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .collect(Collectors.toSet());
        assertEquals(Set.of(), publicMethods.stream().filter(RELAYED::contains).collect(Collectors.toSet()));
        Set<String> relays = Arrays.stream(MinosApplicationTestAccess.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .collect(Collectors.toSet());
        assertEquals(RELAYED, relays);
        Set<String> removed = Set.of("incrementalIndexingPlanner", "invalidationService", "indexerRegistry");
        assertEquals(Set.of(), publicMethods.stream().filter(removed::contains).collect(Collectors.toSet()));
    }

    private static MinosApplicationComposer composer() {
        InvocationHandler handler = (self, method, arguments) -> switch (method.getName()) {
            case "resumableRunMarkers" -> unused(ResumableRunMarkers.class);
            case "indexImportHistoryStore" -> ImportHistoryStores.none();
            case "completeProgramGraphProviders" -> TestProgramGraphProviders.completedArgument(arguments[0]);
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
