package com.minos.registry;

import com.minos.io.BoundedProperties;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

    private final Path file;

    public ProjectPathMappingStore(Path minosHome) {
        Objects.requireNonNull(minosHome, "minosHome");
        file = minosHome.toAbsolutePath().normalize().resolve(RUNTIME_DIRECTORY).resolve(FILE_NAME);
    }

    public Optional<ProjectPathMapping> loadOptional() throws IOException {
        if (!Files.exists(file)) return Optional.empty();
        if (!Files.isRegularFile(file)) {
            throw new IOException("project path mapping is not a regular file: " + file);
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
        Files.createDirectories(file.getParent());
        Properties properties = new Properties();
        properties.setProperty("formatVersion", Integer.toString(CURRENT_FORMAT_VERSION));
        properties.setProperty("hostRoot", mapping.hostRoot());
        properties.setProperty("containerRoot", mapping.containerRoot());
        Path temporary = Files.createTempFile(file.getParent(), FILE_NAME + ".", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(
                    temporary, StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                properties.store(writer, "MINOS project path mapping v1");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public Path file() {
        return file;
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing property " + key);
        }
        return value.trim();
    }
}
