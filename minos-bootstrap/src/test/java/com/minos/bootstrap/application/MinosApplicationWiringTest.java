package com.minos.bootstrap.application;

import com.minos.application.MinosApplication;
import com.minos.application.MinosApplicationTestAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A4 (ADR 0045) : graphe d'instances que {@link MinosApplication} câble entre ses services dérivés. Le
 * regroupement interne par domaine ne doit changer aucune de ces identités.
 *
 * <p>Ces collaborateurs n'ont pas d'accesseur : le test lit les champs privés par réflexion, sans rien
 * élargir dans le code de production (classpath, pas de module JPMS).</p>
 */
class MinosApplicationWiringTest {

    @Test
    void advancedAndSecurityAnalysesReuseTheExposedImpactAndProgramGraphServices(@TempDir Path root) throws Exception {
        try (MinosApplication application = MinosApplication.open(root.resolve("home"))) {
            assertSame(application.impactQuery(), field(application.advancedImpactService(), "baselineImpact"));
            assertSame(application.programGraphService(), field(application.advancedImpactService(), "programGraphs"));
            assertSame(application.programGraphService(), field(application.securityAnalysisService(), "programGraphs"));
        }
    }

    @Test
    void semanticIndexAndHybridSearchShareOneProjectResolverAndChainTheExposedServices(@TempDir Path root)
            throws Exception {
        try (MinosApplication application = MinosApplication.open(root.resolve("home"))) {
            Object sharedResolver = field(application.semanticIndexService(), "projects");
            assertNotNull(sharedResolver);
            assertSame(sharedResolver, field(application.hybridSearchService(), "projects"));

            assertSame(application.semanticIndexService(), field(application.semanticSearchService(), "indexService"));
            assertSame(application.semanticIndexService(), field(application.hybridSearchService(), "semanticIndex"));
            assertSame(application.semanticSearchService(), field(application.hybridSearchService(), "semanticSearch"));
            assertSame(application.hybridSearchService(), field(application.hybridContextBuilder(), "hybridSearch"));
        }
    }

    @Test
    void derivedServicesReadTheExposedStoresAndDiscovery(@TempDir Path root) throws Exception {
        try (MinosApplication application = MinosApplication.open(root.resolve("home"))) {
            assertSame(MinosApplicationTestAccess.snapshotStore(application), field(application.semanticIndexService(), "snapshots"));
            assertSame(application.semanticVectorStore(), field(application.semanticIndexService(), "store"));
            assertSame(MinosApplicationTestAccess.snapshotStore(application), field(application.hybridSearchService(), "snapshots"));
            assertSame(MinosApplicationTestAccess.runtimeObservationStore(application), field(application.runtimeIntelligenceService(),
                    "store"));
            assertSame(MinosApplicationTestAccess.snapshotStore(application), field(application.architectureQuery(), "snapshotStore"));
            assertSame(MinosApplicationTestAccess.discoveryService(application), field(application.architectureQuery(),
                    "discoveryService"));
            assertSame(MinosApplicationTestAccess.discoveryService(application), field(application.projectInspectionService(),
                    "discoveryService"));
            assertSame(MinosApplicationTestAccess.indexStateStore(application), field(application.projectInspectionService(),
                    "stateStore"));
            assertSame(MinosApplicationTestAccess.snapshotStore(application), field(application.projectQueryService(), "snapshotStore"));
            assertSame(MinosApplicationTestAccess.snapshotStore(application), field(application.programGraphService(), "snapshotStore"));
        }
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
