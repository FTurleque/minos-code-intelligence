package com.minos.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * Local learned embedding provider backed by an explicitly configured trusted Ollama endpoint.
 *
 * <p>MINOS permits loopback endpoints for native execution and the fixed {@code minos-ollama}
 * service name used by the managed internal Docker network. Arbitrary remote hosts remain
 * rejected. MINOS never downloads a model from this Java provider.</p>
 */
public final class OllamaEmbeddingProvider implements EmbeddingProvider {

    public static final URI DEFAULT_ENDPOINT = URI.create("http://127.0.0.1:11434/api/embed");
    public static final URI MANAGED_DOCKER_ENDPOINT = URI.create("http://minos-ollama:11434/api/embed");
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final String MANAGED_DOCKER_HOST = "minos-ollama";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

    private final URI endpoint;
    private final String model;
    private final int dimensions;
    private final int timeoutMillis;

    public OllamaEmbeddingProvider(String model, int dimensions) {
        this(DEFAULT_ENDPOINT, model, dimensions, DEFAULT_TIMEOUT);
    }

    public OllamaEmbeddingProvider(URI endpoint, String model, int dimensions, Duration timeout) {
        this.endpoint = validateEndpoint(Objects.requireNonNull(endpoint, "endpoint"));
        this.model = requireText(model, "model");
        if (dimensions < 32 || dimensions > 16_384) {
            throw new IllegalArgumentException("dimensions must be between 32 and 16384");
        }
        this.dimensions = dimensions;
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("timeout must be between 1ms and 5 minutes");
        }
        long millis = timeout.toMillis();
        this.timeoutMillis = Math.toIntExact(Math.max(1L, millis));
    }

    @Override public String id() { return "minos-local-ollama"; }
    @Override public String modelId() { return model; }
    @Override public int dimensions() { return dimensions; }
    public URI endpoint() { return endpoint; }

    @Override
    public SemanticVector embed(String stableKey, String text) throws IOException {
        requireText(stableKey, "stableKey");
        Objects.requireNonNull(text, "text");

        HttpURLConnection connection = (HttpURLConnection) endpoint.toURL().openConnection(Proxy.NO_PROXY);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(timeoutMillis);
        connection.setReadTimeout(timeoutMillis);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        connection.setDoOutput(true);

        byte[] request = requestBody(model, text).getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(request.length);
        try {
            try (OutputStream output = connection.getOutputStream()) { output.write(request); }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            String response = readBounded(stream);
            if (status < 200 || status >= 300) {
                throw new IOException("local Ollama embedding request failed with HTTP " + status
                        + (response.isBlank() ? "" : ": " + response));
            }
            double[] values = parseEmbeddingResponse(response, dimensions);
            return SemanticVector.fromArray(stableKey, values);
        } finally {
            connection.disconnect();
        }
    }

    static URI validateEndpoint(URI endpoint) {
        String scheme = endpoint.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Ollama endpoint must use http or https");
        }
        if (endpoint.getUserInfo() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("Ollama endpoint must not contain user-info or fragment");
        }
        String host = endpoint.getHost();
        if (host == null || !isTrustedHost(host)) {
            throw new IllegalArgumentException("Ollama endpoint must use loopback or the managed Docker service minos-ollama");
        }
        if (endpoint.getPath() == null || endpoint.getPath().isBlank()) {
            throw new IllegalArgumentException("Ollama endpoint must contain an API path");
        }
        return endpoint.normalize();
    }

    static double[] parseEmbeddingResponse(String response, int expectedDimensions) throws IOException {
        Objects.requireNonNull(response, "response");
        JsonNode root = JSON.readTree(response);
        JsonNode embeddings = root == null ? null : root.get("embeddings");
        if (embeddings == null || !embeddings.isArray()) {
            throw new IOException("Ollama response does not contain an embeddings array");
        }
        if (embeddings.size() != 1 || !embeddings.get(0).isArray()) {
            throw new IOException("Ollama response must contain exactly one embedding vector");
        }
        JsonNode vector = embeddings.get(0);
        if (vector.size() != expectedDimensions) {
            throw new IOException("Ollama embedding dimensions mismatch: expected " + expectedDimensions
                    + " but got " + vector.size());
        }
        double[] values = new double[expectedDimensions];
        for (int index = 0; index < expectedDimensions; index++) {
            JsonNode element = vector.get(index);
            if (element == null || !element.isNumber()) {
                throw new IOException("Ollama embedding contains a non-numeric value");
            }
            values[index] = element.doubleValue();
            if (!Double.isFinite(values[index])) {
                throw new IOException("Ollama embedding contains a non-finite value");
            }
        }
        return values;
    }

    static String requestBody(String model, String input) {
        return "{\"model\":\"" + jsonEscape(requireText(model, "model"))
                + "\",\"input\":\"" + jsonEscape(Objects.requireNonNull(input, "input"))
                + "\",\"truncate\":true}";
    }

    private static boolean isTrustedHost(String host) {
        String value = host.toLowerCase(Locale.ROOT);
        if (MANAGED_DOCKER_HOST.equals(value)) return true;
        if ("localhost".equals(value) || "::1".equals(value) || "[::1]".equals(value)) return true;
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) return false;
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) return false;
            for (int index = 0; index < octet.length(); index++) if (!Character.isDigit(octet.charAt(index))) return false;
            int numeric;
            try { numeric = Integer.parseInt(octet); }
            catch (NumberFormatException exception) { return false; }
            if (numeric < 0 || numeric > 255) return false;
        }
        return Integer.parseInt(octets[0]) == 127;
    }

    private static String readBounded(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (InputStream input = stream) {
            byte[] bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) throw new IOException("Ollama response exceeds safety limit");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static String jsonEscape(String value) {
        StringBuilder result = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (current < 0x20) result.append(String.format("\\u%04x", (int) current));
                    else result.append(current);
                }
            }
        }
        return result.toString();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
