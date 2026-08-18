package com.minos.registry;

import com.minos.io.BoundedProperties;
import com.minos.io.DurableAtomicFile;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/** Versioned runtime-only persistence for host/container physical project roots. */
public final class ProjectPathMappingStore {

    public static final int CURRENT_FORMAT_VERSION = 1;
    public static final String RUNTIME_DIRECTORY = "runtime";
    public static final String FILE_NAME = "project-paths.properties";

    private static final long MAX_MAPPING_BYTES = 64L * 1024L;
    private static final Set<String> ALLOWED_KEYS = Set.of("formatVersion", "hostRoot", "containerRoot");

    private final Path home;
    private final Path runtimeDirectory;
    private final Path file;

    public ProjectPathMappingStore(Path minosHome) {
        this.home = Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize();
        this.runtimeDirectory = home.resolve(RUNTIME_DIRECTORY);
        this.file = runtimeDirectory.resolve(FILE_NAME);
    }

    public Optional<ProjectPathMapping> loadOptional() throws IOException {
        validateExistingContainer(home, "MINOS home");
        validateExistingContainer(runtimeDirectory, "project path mapping directory");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("project path mapping is not a regular non-symlink file: " + file);
        }
        Properties properties = BoundedProperties.load(
                file,
                MAX_MAPPING_BYTES,
                ALLOWED_KEYS.size(),
                128,
                16 * 1024,
                "project path mapping"
        );
        for (Object rawKey : properties.keySet()) {
            String key = String.valueOf(rawKey);
            if (!ALLOWED_KEYS.contains(key)) {
                throw new IOException("unknown project path mapping property: " + key);
            }
        }
        try {
            int version = Integer.parseInt(required(properties, "formatVersion"));
            if (version != CURRENT_FORMAT_VERSION) {
                throw new IllegalArgumentException("unsupported project path mapping format: " + version);
            }
            return Optional.of(new ProjectPathMapping(
                    required(properties, "hostRoot"),
                    required(properties, "containerRoot")));
        } catch (IllegalArgumentException exception) {
            throw new IOException("invalid project path mapping: " + exception.getMessage(), exception);
        }
    }

    public void save(ProjectPathMapping mapping) throws IOException {
        Objects.requireNonNull(mapping, "mapping");
        validateExistingContainer(home, "MINOS home");
        validateExistingContainer(runtimeDirectory, "project path mapping directory");
        DurableAtomicFile.ensureDirectory(runtimeDirectory, "project path mapping directory");
        Properties properties = new Properties();
        properties.setProperty("formatVersion", Integer.toString(CURRENT_FORMAT_VERSION));
        properties.setProperty("hostRoot", mapping.hostRoot());
        properties.setProperty("containerRoot", mapping.containerRoot());
        Path temporary = Files.createTempFile(runtimeDirectory, FILE_NAME + ".", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(
                    temporary, StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                properties.store(writer, "MINOS project path mapping v1");
            }
            DurableAtomicFile.replace(temporary, file, "project path mapping replacement");
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public Path file() {
        return file;
    }

    private static void validateExistingContainer(Path directory, String label) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " must be a non-symlink directory: " + directory);
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing property " + key);
        }
        return value.trim();
    }
}
