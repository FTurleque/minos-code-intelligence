package com.minos.adapter.scip;

import com.minos.application.MinosApplication;
import com.minos.application.ProviderPlatformService;
import com.minos.orchestration.CapabilitySupportLevel;
import com.minos.orchestration.IndexerCapability;
import com.minos.orchestration.IndexerProvider;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.ProviderConformanceKit;
import com.minos.runtime.ProviderRuntimeStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M24PolyglotProviderTest {
    private static final Set<String> NEW_PROVIDER_IDS = Set.of(
            ScipIndexerCatalog.SCIP_CLANG_ID,
            ScipIndexerCatalog.SCIP_DOTNET_ID,
            ScipIndexerCatalog.SCIP_GO_ID,
            ScipIndexerCatalog.RUST_ANALYZER_SCIP_ID);

    @Test
    void exposesSevenProvidersWithExhaustiveCapabilityAndOperationalProfiles() {
        List<IndexerProvider> providers = ScipIndexerCatalog.qualifiedM24Providers();
        assertEquals(7, providers.size());
        assertTrue(providers.stream().map(provider -> provider.descriptor().id()).collect(Collectors.toSet())
                .containsAll(NEW_PROVIDER_IDS));

        ProviderConformanceKit kit = new ProviderConformanceKit();
        for (IndexerProvider provider : providers) {
            assertEquals(IndexerCapability.values().length, provider.capabilityProfile().support().size());
            assertFalse(provider.capabilityProfile().limitations().isEmpty());
            assertTrue(provider.operationalProfile().explicit());
            ProviderConformanceKit.ConformanceResult result = kit.evaluate(provider);
            assertEquals(provider.descriptor().id(), result.providerId());
            assertEquals(IndexerCapability.values().length, result.capabilities().size());
            assertFalse(result.runtimeRequirements().isEmpty());
            assertFalse(result.stableIdentityBehavior().isBlank());
            assertFalse(result.provenanceBehavior().isBlank());
        }
    }

    @Test
    void keepsNewProvidersExperimentalUntilPlatformEvidencePromotesThem() {
        Map<String, IndexerProvider> providers = ScipIndexerCatalog.qualifiedM24Providers().stream()
                .collect(Collectors.toMap(provider -> provider.descriptor().id(), Function.identity()));
        for (String id : NEW_PROVIDER_IDS) {
            IndexerProvider provider = providers.get(id);
            assertEquals(IndexerQualification.EXPERIMENTAL, provider.descriptor().qualification());
            assertTrue(provider.operationalProfile().qualificationPlatforms().isEmpty());
            assertEquals(CapabilitySupportLevel.PARTIAL,
                    provider.capabilityProfile().supportOf(IndexerCapability.STABLE_SYMBOL_IDENTITY));
            assertEquals(CapabilitySupportLevel.UNSUPPORTED,
                    provider.capabilityProfile().supportOf(IndexerCapability.CALL_RELATIONS));
            assertEquals(CapabilitySupportLevel.UNSUPPORTED,
                    provider.capabilityProfile().supportOf(IndexerCapability.INCREMENTAL_INDEXING));
        }
    }

    @Test
    void onlyDotnetAndGoClaimManagedInstallationAmongNewProviders() {
        Map<String, IndexerProvider> providers = ScipIndexerCatalog.qualifiedM24Providers().stream()
                .collect(Collectors.toMap(provider -> provider.descriptor().id(), Function.identity()));
        assertEquals(CapabilitySupportLevel.UNSUPPORTED,
                providers.get(ScipIndexerCatalog.SCIP_CLANG_ID).capabilityProfile()
                        .supportOf(IndexerCapability.RUNTIME_INSTALLATION));
        assertEquals(CapabilitySupportLevel.FULL,
                providers.get(ScipIndexerCatalog.SCIP_DOTNET_ID).capabilityProfile()
                        .supportOf(IndexerCapability.RUNTIME_INSTALLATION));
        assertEquals(CapabilitySupportLevel.FULL,
                providers.get(ScipIndexerCatalog.SCIP_GO_ID).capabilityProfile()
                        .supportOf(IndexerCapability.RUNTIME_INSTALLATION));
        assertEquals(CapabilitySupportLevel.UNSUPPORTED,
                providers.get(ScipIndexerCatalog.RUST_ANALYZER_SCIP_ID).capabilityProfile()
                        .supportOf(IndexerCapability.RUNTIME_INSTALLATION));
    }

    @Test
    void publicProviderSurfaceUsesTheSharedM24Catalog(@TempDir Path home) throws Exception {
        MinosApplication application = MinosApplication.open(home);
        ProviderPlatformService platform = ProviderPlatformService.defaults(application);
        Set<String> ids = platform.listProviders().stream().map(ProviderPlatformService.ProviderView::id)
                .collect(Collectors.toSet());
        assertTrue(ids.containsAll(NEW_PROVIDER_IDS));
        assertTrue(ids.containsAll(Set.of("scip-java", "scip-typescript", "scip-python")));

        ProviderPlatformService.ProviderView rust = platform.inspect(ScipIndexerCatalog.RUST_ANALYZER_SCIP_ID);
        assertEquals(ScipIndexerCatalog.RUST_ANALYZER_SCIP_VERSION, rust.version());
        assertEquals("EXPERIMENTAL", rust.qualification());
        assertTrue(rust.operationalProfileExplicit());
    }

    @Test
    void windowsDoesNotPretendScipClangRuntimeIsReady(@TempDir Path home) throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;
        }
        MinosApplication application = MinosApplication.open(home);
        ProviderRuntimeStatus status = application.providerRuntimeManager().inspect(ScipIndexerCatalog.SCIP_CLANG_ID);
        assertEquals(ProviderRuntimeStatus.State.BLOCKED, status.state());
        assertTrue(status.diagnostics().stream().anyMatch(value -> value.contains("Windows")));
    }
}
