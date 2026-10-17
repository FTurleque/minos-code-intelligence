package com.minos.application.semantic;

import com.minos.registry.ProjectPathMapping.RuntimeLocation;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code minos-ollama} n'a de sens que sur le réseau Docker géré : hors Docker, rien ne part (AUD-SEC-07). */
class OllamaEmbeddingProviderRuntimeLocationTest {

    @Test
    void theManagedServiceFromTheDefaultNativeLocationFailsAtUseBeforeAnyNameResolution() {
        OllamaEmbeddingProvider provider = new OllamaEmbeddingProvider(
                OllamaEmbeddingProvider.MANAGED_DOCKER_ENDPOINT, "fixture", 384, Duration.ofSeconds(2));

        IOException failure = assertThrows(IOException.class, () -> provider.embed("query", "secret source code"));

        assertFalse(failure instanceof UnknownHostException, "the name was resolved: " + failure);
        assertTrue(failure.getMessage().contains("minos.semantic.endpoint"), failure.getMessage());
        assertTrue(failure.getMessage().contains("MINOS_RUNTIME_LOCATION=docker"), failure.getMessage());
        assertFalse(failure.getMessage().contains("secret source code"));
    }

    @Test
    void theManagedServiceFromTheDockerLocationIsAcceptedAndNotDeclaredUnusable() {
        OllamaEmbeddingProvider provider = new OllamaEmbeddingProvider(
                OllamaEmbeddingProvider.MANAGED_DOCKER_ENDPOINT, "fixture", 384, Duration.ofSeconds(2),
                RuntimeLocation.DOCKER);

        assertEquals("minos-ollama", provider.endpoint().getHost());
        assertFalse(provider.limitations().contains(OllamaEmbeddingProvider.MANAGED_SERVICE_OUTSIDE_DOCKER_LIMITATION));
    }

    @Test
    void theManagedServiceFromAnExplicitNativeLocationIsConstructedButDeclaredUnusable() {
        OllamaEmbeddingProvider provider = new OllamaEmbeddingProvider(
                OllamaEmbeddingProvider.MANAGED_DOCKER_ENDPOINT, "fixture", 384, Duration.ofSeconds(2),
                RuntimeLocation.NATIVE);

        assertTrue(provider.limitations().contains(OllamaEmbeddingProvider.MANAGED_SERVICE_OUTSIDE_DOCKER_LIMITATION));
        assertThrows(IOException.class, () -> provider.embed("query", "text"));
    }

    @Test
    void aLoopbackEndpointIsUnaffectedByTheLocation() {
        for (RuntimeLocation location : RuntimeLocation.values()) {
            OllamaEmbeddingProvider provider = new OllamaEmbeddingProvider(
                    URI.create("http://127.0.0.1:11434/api/embed"), "fixture", 384, Duration.ofSeconds(2), location);

            assertFalse(provider.limitations().contains(OllamaEmbeddingProvider.MANAGED_SERVICE_OUTSIDE_DOCKER_LIMITATION));
        }
    }

    @Test
    void aHostThatIsNotRecognisedIsStillRefusedWhateverTheLocation() {
        assertThrows(IllegalArgumentException.class, () -> new OllamaEmbeddingProvider(
                URI.create("http://minos-ollama.example.com:11434/api/embed"), "fixture", 384, Duration.ofSeconds(2),
                RuntimeLocation.DOCKER));
    }

    @Test
    void anErrorBodyIsSampledNotRead() throws Exception {
        AtomicLong consumed = new AtomicLong();
        InputStream endless = new InputStream() {
            @Override public int read() {
                consumed.incrementAndGet();
                return 'e';
            }

            @Override public int read(byte[] buffer, int offset, int length) {
                consumed.addAndGet(length);
                Arrays.fill(buffer, offset, offset + length, (byte) 'e');
                return length;
            }
        };

        String excerpt = OllamaEmbeddingProvider.readErrorExcerpt(endless);

        assertTrue(consumed.get() <= 8 * 1024, "consumed " + consumed.get() + " bytes");
        assertTrue(excerpt.length() <= 512, "excerpt length " + excerpt.length());
        assertEquals("", OllamaEmbeddingProvider.readErrorExcerpt(null));
        assertEquals("", OllamaEmbeddingProvider.readErrorExcerpt(new ByteArrayInputStream(new byte[0])));
    }
}
