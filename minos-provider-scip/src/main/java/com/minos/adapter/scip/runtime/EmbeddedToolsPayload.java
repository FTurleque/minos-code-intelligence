package com.minos.adapter.scip.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minos.io.BoundedInputStream;
import com.minos.io.ConfinedFileOpener;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The tools shipped next to the installation ({@code <installation>/tools}): read only, never executed
 * from, and never trusted by birth. Every byte that leaves it is verified against the packaged catalogue
 * (pinned artifacts) or against the manifest of the signed distribution (assembled trees) before MINOS
 * extracts it under {@code MINOS_HOME/tools}.
 */
final class EmbeddedToolsPayload {

    static final String MANIFEST_FILE = "TOOLS-MANIFEST.json";
    static final String DIRECTORY_PROPERTY = "minos.embedded.tools";
    static final String DIRECTORY_VARIABLE = "MINOS_EMBEDDED_TOOLS_DIR";
    static final String KIND_ARTIFACT = "artifact";
    static final String KIND_ASSEMBLED = "assembled";

    private static final long MAX_MANIFEST_BYTES = 1024L * 1024L;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One file of the payload, as the distribution manifest records it. */
    record Entry(String id, String kind, String version, String path, String sha256, long sizeBytes) {
    }

    private final Path directory;
    private final String platform;
    private final List<Entry> entries;

    private EmbeddedToolsPayload(Path directory, String platform, List<Entry> entries) {
        this.directory = directory;
        this.platform = platform;
        this.entries = List.copyOf(entries);
    }

    /** The payload directory of this installation, when there is one. */
    static Optional<Path> locateDirectory() {
        String property = System.getProperty(DIRECTORY_PROPERTY);
        String configured = property != null && !property.isBlank() ? property : System.getenv(DIRECTORY_VARIABLE);
        if (configured != null && !configured.isBlank()) return existingDirectory(configured.trim());
        String launcher = System.getProperty("jpackage.app-path");
        if (launcher == null || launcher.isBlank()) return Optional.empty();
        try {
            Path appDirectory = Path.of(launcher).toAbsolutePath().normalize().getParent();
            Path installation = appDirectory == null ? null : appDirectory.getParent();
            return installation == null ? Optional.empty() : existingDirectory(installation.resolve("tools").toString());
        } catch (InvalidPathException invalid) {
            return Optional.empty();
        }
    }

    private static Optional<Path> existingDirectory(String value) {
        try {
            Path path = Path.of(value).toAbsolutePath().normalize();
            return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? Optional.of(path) : Optional.empty();
        } catch (InvalidPathException invalid) {
            return Optional.empty();
        }
    }

    /** Opens the payload of {@code directory}; fails closed when its manifest is absent or malformed. */
    static EmbeddedToolsPayload open(Path directory) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        Path manifest = root.resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("the tools payload has no " + MANIFEST_FILE);
        }
        JsonNode tree;
        try (BoundedInputStream input = new BoundedInputStream(
                ConfinedFileOpener.openRegularFileNoFollow(manifest), MAX_MANIFEST_BYTES, "tools manifest")) {
            tree = JSON.readTree(input.readAllBytes());
        }
        if (tree == null || tree.path("formatVersion").asInt(-1) != 1) {
            throw new IOException("the tools manifest has an unsupported format");
        }
        String platform = tree.path("platform").asText("");
        if (platform.isBlank()) throw new IOException("the tools manifest names no platform");
        List<Entry> entries = new ArrayList<>();
        JsonNode files = tree.path("files");
        if (!files.isArray()) throw new IOException("the tools manifest lists no files");
        for (JsonNode node : files) {
            String sha256 = node.path("sha256").asText("");
            String path = node.path("path").asText("");
            if (!SHA256.matcher(sha256).matches() || path.isBlank() || node.path("id").asText("").isBlank()) {
                throw new IOException("the tools manifest has a malformed entry");
            }
            entries.add(new Entry(
                    node.path("id").asText(), node.path("kind").asText(KIND_ARTIFACT), node.path("version").asText(""),
                    path, sha256, node.path("sizeBytes").asLong(-1L)));
        }
        return new EmbeddedToolsPayload(root, platform, entries);
    }

    String platform() {
        return platform;
    }

    Optional<Entry> entry(String id, String kind) {
        return entries.stream().filter(entry -> entry.id().equals(id) && entry.kind().equals(kind)).findFirst();
    }

    boolean provides(String id) {
        return entries.stream().anyMatch(entry -> entry.id().equals(id));
    }

    /** The payload file of {@code entry}, confined to the payload directory. */
    Path file(Entry entry) throws IOException {
        Path resolved = confined(entry.path());
        if (!Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("a file listed by the tools manifest is missing: " + entry.id());
        }
        return resolved;
    }

    /**
     * The payload file the catalogue names for {@code artifact}, when this payload carries it. The name
     * comes from the packaged catalogue, not from the payload's own manifest.
     */
    Optional<Path> artifactFile(EmbeddedToolsCatalog.Artifact artifact) throws IOException {
        if (!artifact.embedded() || !platformServes(artifact.platform())) return Optional.empty();
        Path resolved = confined(artifact.payload());
        return Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS) ? Optional.of(resolved) : Optional.empty();
    }

    private boolean platformServes(String artifactPlatform) {
        return artifactPlatform.equals(EmbeddedToolsCatalog.PLATFORM_ANY) || artifactPlatform.equals(platform);
    }

    private Path confined(String relativePath) throws IOException {
        Path relative;
        try {
            relative = Path.of(relativePath);
        } catch (InvalidPathException invalid) {
            throw new IOException("a tools payload path is invalid");
        }
        Path resolved = directory.resolve(relative).normalize();
        if (relative.isAbsolute() || !resolved.startsWith(directory) || resolved.equals(directory)) {
            throw new IOException("a tools payload path points outside the payload");
        }
        return resolved;
    }

    /** Opens {@code file} without following links. */
    static InputStream openNoFollow(Path file) throws IOException {
        return ConfinedFileOpener.openRegularFileNoFollow(file);
    }
}
