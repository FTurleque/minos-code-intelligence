package com.minos.application.semantic;

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
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Le corps d'une réponse d'erreur Ollama est borné à la lecture et assaini dans le message (AUD-SEC-07). */
class OllamaEmbeddingProviderErrorBodyTest {

    @Test
    void aLargeErrorBodyIsBoundedInTheMessage() throws Exception {
        byte[] body = new byte[1024 * 1024];
        Arrays.fill(body, (byte) 'x');

        IOException failure = embedAgainstServerAnswering(500, body);

        assertTrue(failure.getMessage().contains("HTTP 500"), failure.getMessage());
        assertTrue(failure.getMessage().length() < 600, "message length " + failure.getMessage().length());
    }

    @Test
    void anErrorBodyNamingAnAbsolutePathIsOmittedButTheStatusRemains() throws Exception {
        byte[] body = "{\"error\":\"cannot open /home/u/models\"}".getBytes(StandardCharsets.UTF_8);

        IOException failure = embedAgainstServerAnswering(500, body);

        assertTrue(failure.getMessage().contains("HTTP 500"), failure.getMessage());
        assertFalse(failure.getMessage().contains("/home/u/models"), failure.getMessage());
    }

    @Test
    void aHarmlessShortErrorBodyIsKeptOnOneLine() throws Exception {
        byte[] body = "model\r\nnot found".getBytes(StandardCharsets.UTF_8);

        IOException failure = embedAgainstServerAnswering(404, body);

        assertTrue(failure.getMessage().contains("HTTP 404: model  not found"), failure.getMessage());
        assertFalse(failure.getMessage().contains("\n"));
    }

    private static IOException embedAgainstServerAnswering(int status, byte[] body) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread responder = Thread.startVirtualThread(() -> answerOnce(server, status, body));
            OllamaEmbeddingProvider provider = new OllamaEmbeddingProvider(
                    URI.create("http://127.0.0.1:" + server.getLocalPort() + "/api/embed"),
                    "fixture", 32, Duration.ofSeconds(5));
            IOException failure = assertThrows(IOException.class, () -> provider.embed("query", "text"));
            responder.join();
            return failure;
        }
    }

    private static void answerOnce(ServerSocket server, int status, byte[] body) {
        try (Socket socket = server.accept();
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            int contentLength = 0;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                }
            }
            for (int read = 0; read < contentLength; read++) {
                if (reader.read() < 0) break;
            }
            String headers = "HTTP/1.1 " + status + " Error\r\nContent-Type: application/json\r\nContent-Length: "
                    + body.length + "\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(body);
            socket.getOutputStream().flush();
        } catch (IOException ignored) {
            // The client may legitimately stop reading a body it only samples.
        }
    }
}
