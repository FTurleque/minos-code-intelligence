package com.minos.semantic;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaEmbeddingProviderTest {

    @Test
    void requiresLoopbackEndpointAndExplicitModelIdentity() {
        OllamaEmbeddingProvider provider = new OllamaEmbeddingProvider(
                URI.create("http://localhost:11434/api/embed"), "fixture-code-model", 384, Duration.ofSeconds(2));
        assertEquals("minos-local-ollama", provider.id());
        assertEquals("fixture-code-model", provider.modelId());
        assertEquals(384, provider.dimensions());
        assertEquals("localhost", provider.endpoint().getHost());

        assertThrows(IllegalArgumentException.class, () -> new OllamaEmbeddingProvider(
                URI.create("https://example.com/api/embed"), "fixture", 384, Duration.ofSeconds(2)));
        assertThrows(IllegalArgumentException.class, () -> new OllamaEmbeddingProvider(
                URI.create("file:///tmp/embed"), "fixture", 384, Duration.ofSeconds(2)));
    }

    @Test
    void parsesOfficialEmbedResponseShapeAndChecksDimensions() throws Exception {
        double[] parsed = OllamaEmbeddingProvider.parseEmbeddingResponse(
                "{\"model\":\"fixture\",\"embeddings\":[[0.25,-0.5,0.75]]}", 3);
        assertArrayEquals(new double[]{0.25, -0.5, 0.75}, parsed);

        assertThrows(IOException.class, () -> OllamaEmbeddingProvider.parseEmbeddingResponse(
                "{\"embeddings\":[[0.1,0.2]]}", 3));
        assertThrows(IOException.class, () -> OllamaEmbeddingProvider.parseEmbeddingResponse("{}", 3));
    }

    @Test
    void requestBodyEscapesOperatorControlledInput() {
        String body = OllamaEmbeddingProvider.requestBody("code-model", "line 1\n\"quoted\"\\path");
        assertTrue(body.contains("\"model\":\"code-model\""));
        assertTrue(body.contains("line 1\\n\\\"quoted\\\"\\\\path"));
        assertTrue(body.endsWith("\"truncate\":true}"));
    }
}
