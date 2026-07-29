package com.minos.adapter.scip;

import com.minos.application.MinosApplication;
import com.minos.application.ProviderPlatformService;
import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.discovery.ProjectDiscoveryService;
import com.minos.orchestration.IndexerCapability;
import com.minos.orchestration.IndexerNegotiationResult;
import com.minos.orchestration.IndexerProvider;
import com.minos.orchestration.IndexerProviderRegistry;
import com.minos.orchestration.IndexingRequirements;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M17ProviderPlatformTest {

    private final ProjectDiscoveryService discovery = new ProjectDiscoveryService();

    @Test
    void exposesExhaustiveCapabilityProfilesAndLimitations() {
        List<IndexerProvider> providers = ScipIndexerCatalog.qualifiedM17Providers();
        assertEquals(List.of("scip-java", "scip-typescript", "scip-python"),
                providers.stream().map(value -> value.descriptor().id()).toList());
        for (IndexerProvider provider : providers) {
            assertEquals(IndexerCapability.values().length, provider.capabilityProfile().support().size());
            assertFalse(provider.capabilityProfile().limitations().isEmpty());
        }
    }

    @Test
    void negotiatesKotlinMavenPythonAndPnpmTypeScript() throws Exception {
        IndexerProviderRegistry providers = new IndexerProviderRegistry();
        providers.registerAll(ScipIndexerCatalog.qualifiedM17Providers());

        assertSelected(providers, Path.of("fixtures/kotlin/kotlin-maven-simple"), Language.KOTLIN, "scip-java");
        assertSelected(providers, Path.of("fixtures/python/python-simple"), Language.PYTHON, "scip-python");
        assertSelected(providers, Path.of("fixtures/typescript/typescript-pnpm-workspace"),
                Language.TYPESCRIPT, "scip-typescript");
    }

    @Test
    void doesNotInventGradleRuntimeSupport() throws Exception {
        IndexerProviderRegistry providers = new IndexerProviderRegistry();
        providers.registerAll(ScipIndexerCatalog.qualifiedM17Providers());
        ProjectDiscovery gradleKotlin = discovery.discover(Path.of("fixtures/gradle/gradle-kotlin-multi"));
        IndexerNegotiationResult result = providers.negotiationRegistry()
                .negotiate(gradleKotlin, IndexingRequirements.baseline());
        assertFalse(result.complete());
        assertEquals(Set.of(Language.KOTLIN), result.uncoveredLanguages());
    }

    @Test
    void defaultApplicationStillContainsAllM17ProvidersAfterAdditiveExpansion(@TempDir Path home) throws Exception {
        MinosApplication application = MinosApplication.open(home);
        ProviderPlatformService platform = ProviderPlatformService.defaults(application);
        Set<String> ids = platform.listProviders().stream()
                .map(ProviderPlatformService.ProviderView::id)
                .collect(Collectors.toSet());
        assertTrue(ids.containsAll(Set.of("scip-java", "scip-python", "scip-typescript")));
        assertTrue(platform.inspect("scip-python").limitations().stream()
                .anyMatch(value -> value.contains("Python")));
    }

    private void assertSelected(
            IndexerProviderRegistry providers,
            Path fixture,
            Language language,
            String expectedProvider
    ) throws Exception {
        ProjectDiscovery project = discovery.discover(fixture);
        IndexerNegotiationResult result = providers.negotiationRegistry()
                .negotiate(project, IndexingRequirements.baseline());
        assertTrue(result.complete());
        assertEquals(language, result.selections().getFirst().language());
        assertEquals(expectedProvider, result.selections().getFirst().indexer().id());
    }
}
