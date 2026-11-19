package com.minos.application;

import com.minos.dynamic.RuntimeObservationStore;
import com.minos.git.GitIntelligence;
import com.minos.incremental.ProjectFingerprintSnapshotStore;
import com.minos.orchestration.InMemoryIndexStateStore;
import com.minos.orchestration.IndexerProviderCatalog;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotStager;
import com.minos.program.analysis.ProgramGraphProvider;
import com.minos.program.analysis.RelationshipProgramGraphProvider;
import com.minos.program.analysis.TestProgramGraphProviders;
import com.minos.registry.ProjectRegistry;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.semantic.SemanticVectorStore;
import com.minos.storage.StorageBackend;
import com.minos.storage.StorageRetentionService;
import com.minos.store.CodeKnowledgeSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The assembler takes the program graph providers from the composition root (ADR 0087): the production list when the
 * builder gives none, then the completion of the root; an empty production list is refused before any completion.
 */
class ProgramGraphProviderAssemblyTest {

    @TempDir
    Path home;

    @Test
    void anEmptyProductionListIsRefusedBeforeAnyCompletion() {
        AtomicInteger completions = new AtomicInteger();

        MinosApplication.Builder builder = builder(List.of(), completions);

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, builder::build);

        assertEquals("programGraphProviders must not be empty", refusal.getMessage());
        assertEquals(0, completions.get());
    }

    @Test
    void theProductionListOfTheRootIsCompletedByTheRoot() throws Exception {
        AtomicInteger completions = new AtomicInteger();

        try (MinosApplication application = builder(List.of(new RelationshipProgramGraphProvider()), completions)
                .build()) {
            assertEquals(1, completions.get());
            assertEquals(List.of("minos-relationships", "minos-java-source-v1", "minos-program-sidecar-v1"),
                    List.copyOf(application.programGraphService().providerIds()));
        }
    }

    private MinosApplication.Builder builder(List<ProgramGraphProvider> production, AtomicInteger completions) {
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
                .scipArtifactImporter((indexFile, request, snapshots) -> {
                    throw new UnsupportedOperationException("import");
                });
        builder.composer = (MinosApplicationComposer) Proxy.newProxyInstance(
                MinosApplicationComposer.class.getClassLoader(), new Class<?>[] {MinosApplicationComposer.class},
                (self, method, arguments) -> switch (method.getName()) {
                    case "programGraphProviders" -> production;
                    case "completeProgramGraphProviders" -> {
                        completions.incrementAndGet();
                        yield TestProgramGraphProviders.completedArgument(arguments[0]);
                    }
                    case "indexImportHistoryStore" -> ImportHistoryStores.none();
                    case "toString" -> "composer";
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == arguments[0];
                    default -> throw new UnsupportedOperationException("composer." + method.getName());
                });
        return builder;
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
