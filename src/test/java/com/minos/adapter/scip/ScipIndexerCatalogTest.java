package com.minos.adapter.scip;

import com.minos.adapter.scip.runtime.ManagedScipProviderRuntimeManager;
import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscoveryService;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerCapability;
import com.minos.orchestration.IndexerNegotiationResult;
import com.minos.orchestration.IndexerRegistry;
import com.minos.orchestration.IndexingRequirements;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScipIndexerCatalogTest {

    private final ProjectDiscoveryService discoveryService = new ProjectDiscoveryService();

    @Test
    void selectsQualifiedProvidersForJavaAndTypeScriptFixtures() throws IOException {
        IndexerRegistry registry = qualifiedRegistry();

        ProjectDiscovery javaProject = discoveryService.discover(Path.of("fixtures/java/java-multi-module"));
        ProjectDiscovery typeScriptProject = discoveryService.discover(Path.of("fixtures/typescript/typescript-modules"));

        IndexerNegotiationResult javaResult = registry.negotiate(javaProject, IndexingRequirements.baseline());
        IndexerNegotiationResult typeScriptResult = registry.negotiate(typeScriptProject, IndexingRequirements.baseline());

        assertTrue(javaResult.complete());
        assertEquals("scip-java", javaResult.selections().getFirst().indexer().id());
        assertEquals(ManagedScipProviderRuntimeManager.SCIP_JAVA_VERSION,
                javaResult.selections().getFirst().indexer().version());
        assertTrue(typeScriptResult.complete());
        assertEquals("scip-typescript", typeScriptResult.selections().getFirst().indexer().id());
        assertEquals(ManagedScipProviderRuntimeManager.SCIP_TYPESCRIPT_VERSION,
                typeScriptResult.selections().getFirst().indexer().version());
    }

    @Test
    void preservesBuildFailureCapabilityAsymmetryMeasuredInM0() throws IOException {
        IndexerRegistry registry = qualifiedRegistry();
        IndexingRequirements partialIndex = IndexingRequirements.requiring(
                IndexerCapability.SYMBOLS,
                IndexerCapability.REFERENCES,
                IndexerCapability.PARTIAL_INDEX_ON_BUILD_FAILURE
        );

        ProjectDiscovery javaProject = discoveryService.discover(Path.of("fixtures/java/java-multi-module"));
        ProjectDiscovery typeScriptProject = discoveryService.discover(Path.of("fixtures/typescript/typescript-unresolved"));

        IndexerNegotiationResult javaResult = registry.negotiate(javaProject, partialIndex);
        IndexerNegotiationResult typeScriptResult = registry.negotiate(typeScriptProject, partialIndex);

        assertFalse(javaResult.complete());
        assertEquals(java.util.Set.of(Language.JAVA), javaResult.uncoveredLanguages());
        assertTrue(typeScriptResult.complete());
        assertEquals("scip-typescript", typeScriptResult.selections().getFirst().indexer().id());
    }

    @Test
    void doesNotClaimPreciseImplementationRelationsForTypeScript() throws IOException {
        IndexerRegistry registry = qualifiedRegistry();
        ProjectDiscovery typeScriptProject = discoveryService.discover(Path.of("fixtures/typescript/typescript-inheritance"));

        IndexerNegotiationResult result = registry.negotiate(
                typeScriptProject,
                IndexingRequirements.requiring(
                        IndexerCapability.SYMBOLS,
                        IndexerCapability.REFERENCES,
                        IndexerCapability.IMPLEMENTATION_RELATIONS
                )
        );

        assertFalse(result.complete());
        assertEquals(java.util.Set.of(Language.TYPESCRIPT), result.uncoveredLanguages());
    }

    @Test
    void doesNotInventIncrementalIndexingForPinnedScipVersions() {
        assertFalse(ScipIndexerCatalog.scipJava().capabilities().contains(IndexerCapability.INCREMENTAL_INDEXING));
        assertFalse(ScipIndexerCatalog.scipTypeScript().capabilities().contains(IndexerCapability.INCREMENTAL_INDEXING));
        assertTrue(ScipIndexerCatalog.scipJava().limitations().stream()
                .anyMatch(value -> value.contains("incremental indexing has not been qualified")));
        assertTrue(ScipIndexerCatalog.scipTypeScript().limitations().stream()
                .anyMatch(value -> value.contains("incremental indexing has not been qualified")));
    }

    private static IndexerRegistry qualifiedRegistry() {
        IndexerRegistry registry = new IndexerRegistry();
        ScipIndexerCatalog.registerQualifiedM1(registry);
        return registry;
    }
}
