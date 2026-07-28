package com.minos.semantic;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * Local learned embedding provider backed by an explicitly configured loopback Ollama endpoint.
 *
 * <p>MINOS never downloads a model and never permits this provider to target a non-loopback host.
 * The configured model is therefore an operator-controlled local dependency. Quality promotion is
 * handled separately by the M23 learned-model evaluation gate.</p>
 */
public final class OllamaEmbeddingProvider implements EmbeddingProvider {

    public static final URI DEFAULT_ENDPOINT = URI.create("http://127.0.0.1:11434/api/embed");
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
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

    @Override
    public String id() {
        return "minos-local-ollama";
    }

    @Override
    public String modelId() {
        return model;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    public URI endpoint() {
        return endpoint;
    }

    @Override
    public SemanticVector embed(String stableKey, String text) throws IOException {
        requireText(stableKey, "stableKey");
        Objects.requireNonNull(text, "text");

        HttpURLConnection connection = (HttpURLConnection) endpoint.toURL().openConnection();
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
            try (OutputStream output = connection.getOutputStream()) {
                output.write(request);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
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
        if (host == null || !isLoopbackHost(host)) {
            throw new IllegalArgumentException("Ollama endpoint must be loopback-only");
        }
        if (endpoint.getPath() == null || endpoint.getPath().isBlank()) {
            throw new IllegalArgumentException("Ollama endpoint must contain an API path");
        }
        return endpoint.normalize();
    }

    static double[] parseEmbeddingResponse(String response, int expectedDimensions) throws IOException {
        Objects.requireNonNull(response, "response");
        int property = response.indexOf("\"embeddings\"");
        if (property < 0) throw new IOException("Ollama response does not contain embeddings");
        int outer = response.indexOf('[', property);
        if (outer < 0) throw new IOException("Ollama embeddings array is missing");
        int inner = response.indexOf('[', outer + 1);
        if (inner < 0) throw new IOException("Ollama embedding vector is missing");
        int end = matchingBracket(response, inner);
        String body = response.substring(inner + 1, end).trim();
        if (body.isEmpty()) throw new IOException("Ollama embedding vector is empty");
        String[] parts = body.split(",");
        if (parts.length != expectedDimensions) {
            throw new IOException("Ollama embedding dimensions mismatch: expected "
                    + expectedDimensions + " but got " + parts.length);
        }
        double[] values = new double[parts.length];
        for (int index = 0; index < parts.length; index++) {
            try {
                values[index] = Double.parseDouble(parts[index].trim());
            } catch (NumberFormatException exception) {
                throw new IOException("Ollama embedding contains a non-numeric value", exception);
            }
            if (!Double.isFinite(values[index])) throw new IOException("Ollama embedding contains a non-finite value");
        }
        return values;
    }

    static String requestBody(String model, String input) {
        return "{\"model\":\"" + jsonEscape(requireText(model, "model"))
                + "\",\"input\":\"" + jsonEscape(Objects.requireNonNull(input, "input"))
                + "\",\"truncate\":true}";
    }

    private static boolean isLoopbackHost(String host) {
        String value = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(value) || "::1".equals(value) || "[::1]".equals(value)) {
            return true;
        }
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) return false;
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) return false;
            for (int index = 0; index < octet.length(); index++) {
                if (!Character.isDigit(octet.charAt(index))) return false;
            }
            int numeric;
            try {
                numeric = Integer.parseInt(octet);
            } catch (NumberFormatException exception) {
                return false;
            }
            if (numeric < 0 || numeric > 255) return false;
        }
        return Integer.parseInt(octets[0]) == 127;
    }

    private static int matchingBracket(String value, int start) throws IOException {
        int depth = 0;
        for (int index = start; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '[') depth++;
            else if (current == ']' && --depth == 0) return index;
        }
        throw new IOException("unterminated Ollama embedding vector");
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
