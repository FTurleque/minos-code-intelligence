package com.minos.adapter.scip.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minos.io.BoundedInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The single description of the tools MINOS ships or installs, loaded from the packaged
 * {@code embedded-tools.json}.
 *
 * <p>The Java runtime managers, the Docker release image and the Windows distribution build all read
 * (or are checked against) this one file: an expected SHA-256 never comes from a server and is never
 * written twice. The expected hash of an artifact is the one recorded here, inside the JAR.</p>
 */
public final class EmbeddedToolsCatalog {

    public static final String RESOURCE = "embedded-tools.json";
    public static final String PLATFORM_ANY = "any";
    public static final String PLATFORM_WINDOWS_X64 = "windows-x64";
    public static final String PLATFORM_LINUX_X64 = "linux-x64";

    private static final long MAX_CATALOG_BYTES = 256L * 1024L;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A pinned upstream artifact: the hash is the one the publisher's file must have. */
    public record Artifact(
            String id,
            String platform,
            String version,
            String format,
            String url,
            String sha256,
            long sizeBytes,
            String license,
            boolean embedded,
            String payload
    ) {
        public Artifact {
            requireText(id, "id");
            requireText(platform, "platform");
            requireText(version, "version");
            requireText(format, "format");
            requireText(url, "url");
            requireText(license, "license");
            if (!SHA256.matcher(Objects.requireNonNull(sha256, "sha256")).matches()) {
                throw new IllegalArgumentException("artifact " + id + " must carry a lowercase hex SHA-256");
            }
            if (!url.startsWith("https://")) {
                throw new IllegalArgumentException("artifact " + id + " must be served over HTTPS");
            }
            if (sizeBytes < 0L) {
                throw new IllegalArgumentException("artifact " + id + " has a negative size");
            }
            if (embedded && (payload == null || payload.isBlank())) {
                throw new IllegalArgumentException("embedded artifact " + id + " must name its payload file");
            }
        }
    }

    /** A component built at release time from a repository-owned recipe; no upstream pins its bytes. */
    public record Assembled(
            String id,
            String platform,
            String provider,
            String version,
            String recipe,
            String license,
            boolean embedded,
            String payload
    ) {
        public Assembled {
            requireText(id, "id");
            requireText(platform, "platform");
            requireText(provider, "provider");
            requireText(version, "version");
            requireText(recipe, "recipe");
            requireText(license, "license");
            if (embedded && (payload == null || payload.isBlank())) {
                throw new IllegalArgumentException("embedded component " + id + " must name its payload file");
            }
        }
    }

    /** One provider and the components it needs from MINOS (not from the project's own toolchain). */
    public record ProviderTools(String id, String version, String license, List<String> components) {
        public ProviderTools {
            requireText(id, "id");
            requireText(version, "version");
            requireText(license, "license");
            components = List.copyOf(Objects.requireNonNull(components, "components"));
        }
    }

    private final List<ProviderTools> providers;
    private final List<Artifact> artifacts;
    private final List<Assembled> assembled;

    private EmbeddedToolsCatalog(List<ProviderTools> providers, List<Artifact> artifacts, List<Assembled> assembled) {
        this.providers = List.copyOf(providers);
        this.artifacts = List.copyOf(artifacts);
        this.assembled = List.copyOf(assembled);
    }

    private static final class Holder {
        static final EmbeddedToolsCatalog INSTANCE = loadPackaged();
    }

    /** The catalogue packaged in this JAR. */
    public static EmbeddedToolsCatalog load() {
        return Holder.INSTANCE;
    }

    private static EmbeddedToolsCatalog loadPackaged() {
        try (InputStream raw = EmbeddedToolsCatalog.class.getResourceAsStream(RESOURCE)) {
            if (raw == null) throw new IOException("packaged tools catalogue is missing: " + RESOURCE);
            try (BoundedInputStream bounded = new BoundedInputStream(raw, MAX_CATALOG_BYTES, "tools catalogue")) {
                return parse(JSON.readTree(bounded.readAllBytes()));
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("tools catalogue cannot be loaded", failure);
        }
    }

    static EmbeddedToolsCatalog parse(JsonNode root) {
        if (root.path("formatVersion").asInt(-1) != 1) {
            throw new IllegalArgumentException("tools catalogue formatVersion must be 1");
        }
        List<ProviderTools> providers = new ArrayList<>();
        for (JsonNode node : array(root, "providers")) {
            List<String> components = new ArrayList<>();
            for (JsonNode component : array(node, "components")) components.add(component.asText());
            providers.add(new ProviderTools(
                    text(node, "id"), text(node, "version"), text(node, "license"), components));
        }
        List<Artifact> artifacts = new ArrayList<>();
        for (JsonNode node : array(root, "artifacts")) {
            artifacts.add(new Artifact(
                    text(node, "id"), text(node, "platform"), text(node, "version"), text(node, "format"),
                    text(node, "url"), text(node, "sha256"), node.path("sizeBytes").asLong(0L),
                    text(node, "license"), node.path("embedded").asBoolean(false),
                    node.hasNonNull("payload") ? node.get("payload").asText() : null));
        }
        List<Assembled> assembled = new ArrayList<>();
        for (JsonNode node : array(root, "assembled")) {
            assembled.add(new Assembled(
                    text(node, "id"), text(node, "platform"), text(node, "provider"), text(node, "version"),
                    text(node, "recipe"), text(node, "license"), node.path("embedded").asBoolean(false),
                    node.hasNonNull("payload") ? node.get("payload").asText() : null));
        }
        return new EmbeddedToolsCatalog(providers, artifacts, assembled);
    }

    public List<ProviderTools> providers() {
        return providers;
    }

    public List<Artifact> artifacts() {
        return artifacts;
    }

    public List<Assembled> assembledComponents() {
        return assembled;
    }

    public ProviderTools provider(String providerId) {
        return providers.stream()
                .filter(provider -> provider.id().equals(providerId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("provider is not in the tools catalogue: " + providerId));
    }

    /** The artifact {@code id} for {@code platform}, or the platform-neutral one. */
    public Artifact artifact(String id, String platform) {
        return findArtifact(id, platform).orElseThrow(() -> new IllegalArgumentException(
                "no pinned artifact " + id + " for platform " + platform));
    }

    public Optional<Artifact> findArtifact(String id, String platform) {
        Optional<Artifact> exact = artifacts.stream()
                .filter(artifact -> artifact.id().equals(id) && artifact.platform().equals(platform))
                .findFirst();
        if (exact.isPresent()) return exact;
        return artifacts.stream()
                .filter(artifact -> artifact.id().equals(id) && artifact.platform().equals(PLATFORM_ANY))
                .findFirst();
    }

    public Optional<Assembled> findAssembled(String id, String platform) {
        return assembled.stream()
                .filter(component -> component.id().equals(id) && component.platform().equals(platform))
                .findFirst();
    }

    /** The platform this JVM runs on, as the catalogue spells it; {@code system property} first, for tests. */
    public static String currentPlatform() {
        String override = System.getProperty("minos.tools.platform");
        if (override != null && !override.isBlank()) return override.trim();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        if (x64 && os.contains("win")) return PLATFORM_WINDOWS_X64;
        if (x64 && os.contains("linux")) return PLATFORM_LINUX_X64;
        return os.replace(' ', '-') + "-" + arch;
    }

    private static Iterable<JsonNode> array(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isArray()) throw new IllegalArgumentException("tools catalogue field must be an array: " + field);
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) throw new IllegalArgumentException("tools catalogue field must be text: " + field);
        return value.asText();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
