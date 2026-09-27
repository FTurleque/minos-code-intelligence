package com.minos.adapter.scip;

import com.minos.orchestration.CapabilitySupportLevel;
import com.minos.orchestration.IndexerCapability;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R1 (ADR 0039, risque « provider non déterministe ») : la capacité {@code RESUMABLE_ARTIFACT}
 * est une décision par provider, prise après revue de reproductibilité, jamais une valeur par
 * défaut. Seul scip-java la déclare ; tout autre provider qui la gagnerait ici devrait d'abord
 * passer par cette revue.
 */
class ScipResumableArtifactCapabilityTest {

    @Test
    void scipJavaDeclaresResumableArtifactInItsDescriptorAndProfile() {
        IndexerDescriptor java = ScipIndexerCatalog.scipJava();

        assertTrue(java.capabilities().contains(IndexerCapability.RESUMABLE_ARTIFACT),
                "scip-java artifacts are reproducible from the fingerprinted sources and Maven descriptors");
        assertEquals(CapabilitySupportLevel.FULL,
                ScipIndexerCatalog.scipJavaProfile().supportOf(IndexerCapability.RESUMABLE_ARTIFACT));
    }

    @Test
    void everyOtherCatalogProviderLeavesResumableArtifactUnsupported() {
        List<IndexerProvider> providers = ScipIndexerCatalog.qualifiedM24Providers();
        assertTrue(providers.size() >= 7, "the M24 catalog covers Java, TypeScript, Python and the polyglot providers");

        for (IndexerProvider provider : providers) {
            IndexerDescriptor descriptor = provider.descriptor();
            if ("scip-java".equals(descriptor.id())) continue;
            assertFalse(descriptor.capabilities().contains(IndexerCapability.RESUMABLE_ARTIFACT),
                    descriptor.id() + " must not claim RESUMABLE_ARTIFACT without a reproducibility review");
            assertEquals(CapabilitySupportLevel.UNSUPPORTED,
                    provider.capabilityProfile().supportOf(IndexerCapability.RESUMABLE_ARTIFACT),
                    descriptor.id() + " profile must state UNSUPPORTED explicitly");
        }
        assertEquals(1, providers.stream()
                .filter(provider -> provider.descriptor().capabilities().contains(IndexerCapability.RESUMABLE_ARTIFACT))
                .count(), "exactly one provider is qualified for artifact reuse");
    }
}
