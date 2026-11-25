package com.minos.application;

import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.domain.git.ActivityQuery;
import com.minos.domain.git.ActivityReport;
import com.minos.domain.git.RepositoryView;
import com.minos.dynamic.RuntimeObservationStore;
import com.minos.git.GitIntelligence;
import com.minos.incremental.ProjectFingerprintSnapshotStore;
import com.minos.orchestration.CapabilitySupportLevel;
import com.minos.orchestration.InMemoryIndexStateStore;
import com.minos.orchestration.IndexerCapability;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerProvider;
import com.minos.orchestration.IndexerProviderCatalog;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotStager;
import com.minos.orchestration.ProviderCapabilityProfile;
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

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The public entry points that take an application delegate to the facade, which assembles their collaborators from
 * its ports (AUD-ARC-14), and the facade still requires every collaborator of its composition.
 */
class LocalOperationsAssemblyTest {

    @TempDir Path home;

    @Test
    void theProviderPlatformReadsTheCatalogAtEachCall() throws Exception {
        AtomicReference<List<IndexerProvider>> providers = new AtomicReference<>(List.of(provider("first")));
        IndexerProviderCatalog catalog = providers::get;
        try (MinosApplication application = builder(catalog, unused(GitIntelligence.class)).build()) {
            assertEquals(List.of("first"), ids(ProviderPlatformService.defaults(application)));

            providers.set(List.of(provider("second"), provider("third")));

            assertEquals(List.of("second", "third"), ids(ProviderPlatformService.defaults(application)));
        }
        assertThrows(NullPointerException.class, () -> ProviderPlatformService.defaults(null));
    }

    @Test
    void theGitQueryOfAnApplicationAsksItsGitPort() throws Exception {
        ActivityQuery query = new ActivityQuery(Instant.parse("2026-09-01T00:00:00Z"), 10, 20, 3, true);
        RepositoryView view = new RepositoryView(
                "repo-1", "work/demo", null, "main", "abc123", false, false, true, List.of());
        ActivityReport report = new ActivityReport(view, query, 7, true, false, List.of(), List.of(), List.of(), List.of());
        Path root = home.resolve("project");
        GitIntelligence git = new GitIntelligence() {
            @Override public RepositoryView inspect(Path projectRoot) {
                assertEquals(root, projectRoot);
                return view;
            }

            @Override public ActivityReport analyze(Path projectRoot, ActivityQuery activity) {
                assertEquals(root, projectRoot);
                assertSame(query, activity);
                return report;
            }
        };
        try (MinosApplication application = builder(List::of, git).build()) {
            LocalProjectGitQuery gitQuery = new LocalProjectGitQuery(application);

            assertSame(view, gitQuery.inspect(root));
            assertSame(report, gitQuery.analyze(root, query));
        }
        assertThrows(NullPointerException.class, () -> new LocalProjectGitQuery((MinosApplication) null));
    }

    @Test
    void theSymbolQueryOfAnApplicationIsBuiltOverItsQueryService() throws Exception {
        try (MinosApplication application = builder(List::of, unused(GitIntelligence.class)).build()) {
            new LocalProjectSymbolQuery(application);
        }
        assertThrows(NullPointerException.class, () -> new LocalProjectSymbolQuery((MinosApplication) null));
    }

    @Test
    void anApplicationWithoutScipImporterIsRefused() {
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
                .providerCatalog(List::of)
                .providerRuntimeManager(unused(ProviderRuntimeManager.class))
                .snapshotLifecycle(unused(SnapshotStager.class), unused(SnapshotPromoter.class))
                .gitIntelligence(unused(GitIntelligence.class))
                .programGraphProviders(TestProgramGraphProviders.production());
        builder.composer = composer();

        NullPointerException refused = assertThrows(NullPointerException.class, builder::build);

        assertEquals("scipArtifactImporter", refused.getMessage());
    }

    private MinosApplication.Builder builder(IndexerProviderCatalog catalog, GitIntelligence git) {
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
                .providerCatalog(catalog)
                .providerRuntimeManager(unused(ProviderRuntimeManager.class))
                .snapshotLifecycle(unused(SnapshotStager.class), unused(SnapshotPromoter.class))
                .gitIntelligence(git)
                .programGraphProviders(TestProgramGraphProviders.production())
                .scipArtifactImporter((indexFile, request, store) -> {
                    throw new UnsupportedOperationException("import");
                });
        builder.composer = composer();
        return builder;
    }

    private static List<String> ids(ProviderPlatformService service) {
        return service.listStaticProfiles().stream().map(ProviderPlatformService.ProviderView::id).toList();
    }

    private static IndexerProvider provider(String id) {
        IndexerDescriptor descriptor = new IndexerDescriptor(id, "1.0", id, Set.of(Language.JAVA),
                Set.of(BuildSystem.MAVEN), Set.of(IndexerCapability.SYMBOLS),
                IndexerQualification.QUALIFIED_WITH_CONSTRAINTS, 10, List.of("fixture limitation"));
        EnumMap<IndexerCapability, CapabilitySupportLevel> support = new EnumMap<>(IndexerCapability.class);
        for (IndexerCapability capability : IndexerCapability.values()) {
            support.put(capability, CapabilitySupportLevel.FULL);
        }
        ProviderCapabilityProfile profile = new ProviderCapabilityProfile(id, support, List.of("fixture limitation"));
        return new IndexerProvider() {
            @Override public IndexerDescriptor descriptor() { return descriptor; }
            @Override public ProviderCapabilityProfile capabilityProfile() { return profile; }
        };
    }

    /** A composition root that hands out only what the builder does not supply; scipArtifactImporter() is null. */
    private static MinosApplicationComposer composer() {
        return (MinosApplicationComposer) Proxy.newProxyInstance(MinosApplicationComposer.class.getClassLoader(),
                new Class<?>[]{MinosApplicationComposer.class}, (self, method, arguments) -> switch (method.getName()) {
                    case "resumableRunMarkers" -> unused(ResumableRunMarkers.class);
                    case "indexImportHistoryStore" -> ImportHistoryStores.none();
                    case "completeProgramGraphProviders" -> TestProgramGraphProviders.completedArgument(arguments[0]);
                    case "scipArtifactImporter" -> null;
                    case "toString" -> "composer";
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == arguments[0];
                    default -> throw new UnsupportedOperationException("composer." + method.getName());
                });
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
