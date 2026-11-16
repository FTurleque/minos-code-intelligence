package com.minos.api;

import com.minos.diagnostics.UsageException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** The bounds of the semantic and hybrid queries are inputs of the caller, reported as usage errors. */
class SemanticQueryUsageTest {

    @Test
    void aSemanticQueryOutOfBoundsIsAUsageError() {
        assertThrows(UsageException.class, () -> new SemanticCodeIntelligenceApi.SemanticQuery(" ", 5, 0.0));
        assertThrows(UsageException.class, () -> new SemanticCodeIntelligenceApi.SemanticQuery("q", 0, 0.0));
        assertThrows(UsageException.class, () -> new SemanticCodeIntelligenceApi.SemanticQuery("q", 5, 2.0));
        assertThrows(UsageException.class, () -> new SemanticCodeIntelligenceApi.SemanticQuery("q", 5, Double.NaN));
    }

    @Test
    void aHybridQueryOutOfBoundsIsAUsageError() {
        assertThrows(UsageException.class, () -> new SemanticCodeIntelligenceApi.HybridQuery(" ", 5, 0.5));
        assertThrows(UsageException.class, () -> new SemanticCodeIntelligenceApi.HybridQuery("q", 0, 0.5));
        assertThrows(UsageException.class, () -> new SemanticCodeIntelligenceApi.HybridQuery("q", 5, -0.5));
        assertThrows(UsageException.class, () -> new SemanticCodeIntelligenceApi.HybridQuery("q", 5, Double.NaN));
    }
}
