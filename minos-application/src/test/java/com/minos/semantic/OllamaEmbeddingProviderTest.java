package com.minos.semantic;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaEmbeddingProviderTest {

    @Test
    void requiresNumericLoopbackOrLocalhostAndExplicitModelIdentity() {
        OllamaEmbeddingProvider provider = new OllamaEmbeddingProvider(
                URI.create("http://localhost:11434/api/embed"), "fixture-code-model", 384, Duration.ofSeconds(2));
        assertEquals("minos-local-ollama", provider.id());
        assertEquals("fixture-code-model", provider.modelId());
        assertEquals(384, provider.dimensions());
        assertEquals("localhost", provider.endpoint().getHost());

        new OllamaEmbeddingProvider(
                URI.create("http://127.255.0.1:11434/api/embed"), "fixture", 384, Duration.ofSeconds(2));
        new OllamaEmbeddingProvider(
                URI.create("http://[::1]:11434/api/embed"), "fixture", 384, Duration.ofSeconds(2));

        assertThrows(IllegalArgumentException.class, () -> new OllamaEmbeddingProvider(
                URI.create("https://example.com/api/embed"), "fixture", 384, Duration.ofSeconds(2)));
        assertThrows(IllegalArgumentException.class, () -> new OllamaEmbeddingProvider(
                URI.create("http://127.example.com:11434/api/embed"), "fixture", 384, Duration.ofSeconds(2)));
        assertThrows(IllegalArgumentException.class, () -> new OllamaEmbeddingProvider(
                URI.create("http://127.0.0.1.example.com:11434/api/embed"), "fixture", 384, Duration.ofSeconds(2)));
        assertThrows(IllegalArgumentException.class, () -> new OllamaEmbeddingProvider(
                URI.create("file:///tmp/embed"), "fixture", 384, Duration.ofSeconds(2)));
    }

    @Test
    void callsLoopbackEmbedEndpointAndReturnsValidatedVector() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread responder = Thread.startVirtualThread(() -> {
                try (Socket socket = server.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                    int contentLength = 0;
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                            contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                        }
                    }
                    char[] body = new char[contentLength];
                    int offset = 0;
                    while (offset < body.length) {
                        int read = reader.read(body, offset, body.length - offset);
                        if (read < 0) break;
                        offset += read;
                    }
                    requestBody.set(new String(body, 0, offset));
                    String vector = IntStream.range(0, 32)
                            .mapToObj(index -> index == 0 ? "1.0" : "0.0")
                            .collect(Collectors.joining(","));
                    byte[] payload = ("{\"model\":\"fixture\",\"embeddings\":[[" + vector + "]]}")
                            .getBytes(StandardCharsets.UTF_8);
                    String headers = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                            + payload.length + "\r\nConnection: close\r\n\r\n";
                    socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(payload);
                    socket.getOutputStream().flush();
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });

            OllamaEmbeddingProvider provider = new OllamaEmbeddingProvider(
                    URI.create("http://127.0.0.1:" + server.getLocalPort() + "/api/embed"),
                    "fixture", 32, Duration.ofSeconds(2));
            SemanticVector result = provider.embed("query", "find authentication guard");
            responder.join();

            assertEquals(32, result.dimensions());
            assertEquals(1.0, result.valueAt(0));
            assertTrue(requestBody.get().contains("\"model\":\"fixture\""));
            assertTrue(requestBody.get().contains("find authentication guard"));
        }
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
