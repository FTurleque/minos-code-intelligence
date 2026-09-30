package com.minos.application.semantic;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Q13 — caractérise la copie de {@code requireText} propre à {@link OllamaEmbeddingProvider} avant la
 * mutualisation : c'est la seule qui rend le texte ROGNÉ (le nom du modèle) ; la clé stable est seulement validée.
 */
class OllamaEmbeddingProviderValidationTest {

    private static final URI ENDPOINT = URI.create("http://127.0.0.1:11434/api/embed");

    @Test
    void theModelNameIsTrimmed() {
        OllamaEmbeddingProvider provider = new OllamaEmbeddingProvider(
                ENDPOINT, "  nomic-embed-text \t", 64, Duration.ofSeconds(1));

        assertEquals("nomic-embed-text", provider.modelId());
    }

    @Test
    void aBlankModelIsRefusedWithItsName() {
        IllegalArgumentException blank = assertThrows(IllegalArgumentException.class,
                () -> new OllamaEmbeddingProvider(ENDPOINT, "   ", 64, Duration.ofSeconds(1)));
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> new OllamaEmbeddingProvider(ENDPOINT, null, 64, Duration.ofSeconds(1)));

        assertEquals("model must not be blank", blank.getMessage());
        assertEquals("model must not be blank", missing.getMessage());
    }

    @Test
    void aBlankStableKeyIsRefusedBeforeAnyNetworkCall() {
        OllamaEmbeddingProvider provider = new OllamaEmbeddingProvider(
                ENDPOINT, "model", 64, Duration.ofSeconds(1));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> provider.embed(" ", "text"));

        assertEquals("stableKey must not be blank", failure.getMessage());
    }
}
