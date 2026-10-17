package com.minos.application.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minos.diagnostics.PublicErrorMessages;
import com.minos.output.json.DeterministicJson;
import com.minos.registry.ProjectPathMapping.RuntimeLocation;
import com.minos.semantic.SemanticVector;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static com.minos.domain.Preconditions.requireText;

/**
 * Local learned embedding provider backed by an explicitly configured trusted Ollama endpoint.
 *
 * <p>MINOS permits loopback endpoints for native execution and the fixed {@code minos-ollama}
 * service name used by the managed internal Docker network. Arbitrary remote hosts remain
 * rejected. MINOS never downloads a model from this Java provider.</p>
 *
 * <p>The {@code minos-ollama} name is a single-label host: outside the Docker network it would be
 * resolved by the local DNS suffix list, LLMNR or NBT-NS, and any machine answering could receive the
 * source code sent for embedding. It is therefore only joined from the Docker runtime. A provider built
 * for another runtime location is still constructed -- a {@code MINOS_HOME} written for Docker must stay
 * openable by a native launcher -- but every {@link #embed} refuses before any name resolution, and
 * {@link #limitations()} declares it.</p>
 */
public final class OllamaEmbeddingProvider implements EmbeddingProvider {

    public static final URI DEFAULT_ENDPOINT = URI.create("http://127.0.0.1:11434/api/embed");
    public static final URI MANAGED_DOCKER_ENDPOINT = URI.create("http://minos-ollama:11434/api/embed");
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final String MANAGED_DOCKER_HOST = "minos-ollama";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ERROR_BODY_BYTES = 4 * 1024;
    private static final int MAX_ERROR_EXCERPT_CHARS = 512;
    public static final String MANAGED_SERVICE_OUTSIDE_DOCKER_LIMITATION = "OLLAMA_MANAGED_SERVICE_UNREACHABLE_OUTSIDE_DOCKER";

    private final URI endpoint;
    private final String model;
    private final int dimensions;
    private final int timeoutMillis;
    private final boolean managedServiceOutsideDocker;

    public OllamaEmbeddingProvider(String model, int dimensions) {
        this(DEFAULT_ENDPOINT, model, dimensions, DEFAULT_TIMEOUT);
    }

    /** Built for the native runtime location, the strictest one. */
    public OllamaEmbeddingProvider(URI endpoint, String model, int dimensions, Duration timeout) {
        this(endpoint, model, dimensions, timeout, RuntimeLocation.NATIVE);
    }

    public OllamaEmbeddingProvider(
            URI endpoint, String model, int dimensions, Duration timeout, RuntimeLocation runtimeLocation) {
        this.endpoint = validateEndpoint(Objects.requireNonNull(endpoint, "endpoint"));
        this.managedServiceOutsideDocker = MANAGED_DOCKER_HOST.equalsIgnoreCase(this.endpoint.getHost())
                && Objects.requireNonNull(runtimeLocation, "runtimeLocation") != RuntimeLocation.DOCKER;
        this.model = requireText(model, "model").trim();
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
    @Override public List<String> limitations() {
        return managedServiceOutsideDocker
                ? List.of("LEARNED_MODEL_QUALITY_IS_CONFIGURATION_SPECIFIC", "SEMANTIC_RESULTS_REMAIN_HEURISTIC",
                        MANAGED_SERVICE_OUTSIDE_DOCKER_LIMITATION)
                : List.of("LEARNED_MODEL_QUALITY_IS_CONFIGURATION_SPECIFIC", "SEMANTIC_RESULTS_REMAIN_HEURISTIC");
    }
    @Override public String modelId() { return model; }
    @Override public int dimensions() { return dimensions; }
    public URI endpoint() { return endpoint; }

    @Override
    public SemanticVector embed(String stableKey, String text) throws IOException {
        requireText(stableKey, "stableKey");
        Objects.requireNonNull(text, "text");
        if (managedServiceOutsideDocker) {
            throw new IOException("Ollama configuration error: the semantic endpoint (minos.semantic.endpoint) "
                    + "designates the managed Docker service " + MANAGED_DOCKER_HOST + ", which is only reachable "
                    + "from the Docker runtime (MINOS_RUNTIME_LOCATION=docker); use a loopback endpoint such as "
                    + "127.0.0.1 for native execution");
        }

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
            if (status < 200 || status >= 300) {
                String excerpt = readErrorExcerpt(stream);
                throw new IOException("local Ollama embedding request failed with HTTP " + status
                        + (excerpt.isEmpty() ? "" : ": " + excerpt));
            }
            String response = readBounded(stream);
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
        return DeterministicJson.render(DeterministicJson.object(
                "model", requireText(model, "model").trim(),
                "input", Objects.requireNonNull(input, "input"),
                "truncate", true));
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

    /**
     * What an error response says, safe to put in a message: at most {@value #MAX_ERROR_BODY_BYTES} bytes are
     * read -- the body is not trusted to be small -- reduced to {@value #MAX_ERROR_EXCERPT_CHARS} characters on
     * one line, and left out altogether when it looks sensitive (a path, a credential).
     */
    static String readErrorExcerpt(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (InputStream input = stream) {
            String body = new String(input.readNBytes(MAX_ERROR_BODY_BYTES), StandardCharsets.UTF_8);
            String excerpt = body.length() > MAX_ERROR_EXCERPT_CHARS ? body.substring(0, MAX_ERROR_EXCERPT_CHARS) : body;
            return PublicErrorMessages.sanitize(excerpt, "");
        }
    }

    private static String readBounded(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (InputStream input = stream) {
            byte[] bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) throw new IOException("Ollama response exceeds safety limit");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
