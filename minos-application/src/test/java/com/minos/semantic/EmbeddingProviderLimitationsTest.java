package com.minos.semantic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A2 — les limitations d'un fournisseur d'embedding sont portées par le port, plus déduites de sa
 * classe concrète par SemanticIndexService. Mêmes valeurs, même ordre qu'avant le déplacement.
 */
class EmbeddingProviderLimitationsTest {

    @Test
    void referenceProvidersDeclareExactlyTheLimitationsSemanticStatusesUsedToHardCode() {
        assertEquals(List.of("LOCAL_HASH_EMBEDDING_NOT_LANGUAGE_MODEL"), new LocalHashEmbeddingProvider().limitations());
        assertEquals(List.of("LEARNED_MODEL_QUALITY_IS_CONFIGURATION_SPECIFIC", "SEMANTIC_RESULTS_REMAIN_HEURISTIC"),
                new OllamaEmbeddingProvider("a2-model", 32).limitations());
    }

    @Test
    void anyOtherProviderDeclaresNoLimitationByDefault() {
        EmbeddingProvider thirdParty = new EmbeddingProvider() {
            @Override public String id() { return "third-party"; }
            @Override public String modelId() { return "model"; }
            @Override public int dimensions() { return 32; }
            @Override public SemanticVector embed(String stableKey, String text) {
                throw new UnsupportedOperationException();
            }
        };
        assertEquals(List.of(), thirdParty.limitations());
    }
}
