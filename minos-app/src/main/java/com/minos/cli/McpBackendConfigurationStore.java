package com.minos.cli;

import com.minos.io.BoundedProperties;
import com.minos.io.DurableAtomicFile;
import com.minos.io.PrivateLocalStorage;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/** Atomic, fail-closed persistence for the MCP runtime backend selection. */
public final class McpBackendConfigurationStore {

    public static final String RUNTIME_DIRECTORY = "runtime";
    public static final String FILE_NAME = "backend.properties";

    private static final long MAX_CONFIGURATION_BYTES = 64L * 1024L;
    private static final int MAX_CONFIGURATION_ENTRIES = 16;
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "formatVersion", "backend", "docker.containerName", "docker.probeTimeoutMillis");

    private final Path home;
    private final Path file;

    public McpBackendConfigurationStore(Path minosHome) {
        this.home = Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize();
        this.file = home.resolve(RUNTIME_DIRECTORY).resolve(FILE_NAME);
    }

    /**
     * Loads the explicit backend configuration. Existing pre-M29 homes are migrated once to native.
     * MINOS_HOME itself, the runtime directory and an existing configuration file are each verified
     * owner-only before configuration bytes are read.
     */
    public synchronized McpBackendConfiguration loadOrMigrate() throws IOException {
        ensurePrivateRuntimeDirectory();
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            McpBackendConfiguration configuration = McpBackendConfiguration.nativeDefault();
            save(configuration);
            return configuration;
        }
        secureExistingConfigurationFile();
        Properties properties = BoundedProperties.load(
                file,
                MAX_CONFIGURATION_BYTES,
                MAX_CONFIGURATION_ENTRIES,
                128,
                1024,
                "MCP backend configuration"
        );
        for (Object rawKey : properties.keySet()) {
            String key = String.valueOf(rawKey);
            if (!ALLOWED_KEYS.contains(key)) {
                throw new IOException("unknown MCP backend configuration property: " + key);
            }
        }
        try {
            int formatVersion = Integer.parseInt(required(properties, "formatVersion"));
            McpBackend backend = McpBackend.parse(required(properties, "backend"));
            String container = required(properties, "docker.containerName");
            long timeoutMillis = Long.parseLong(required(properties, "docker.probeTimeoutMillis"));
            return new McpBackendConfiguration(formatVersion, backend, container, Duration.ofMillis(timeoutMillis));
        } catch (IllegalArgumentException exception) {
            throw new IOException("invalid MCP backend configuration: " + exception.getMessage(), exception);
        }
    }

    public synchronized void save(McpBackendConfiguration configuration) throws IOException {
        Objects.requireNonNull(configuration, "configuration");
        ensurePrivateRuntimeDirectory();
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            secureExistingConfigurationFile();
        }
        Properties properties = new Properties();
        properties.setProperty("formatVersion", Integer.toString(configuration.formatVersion()));
        properties.setProperty("backend", configuration.backend().configurationValue());
        properties.setProperty("docker.containerName", configuration.dockerContainerName());
        properties.setProperty("docker.probeTimeoutMillis", Long.toString(configuration.dockerProbeTimeout().toMillis()));

        Path temporary = PrivateLocalStorage.createPrivateTempFile(file.getParent(), FILE_NAME + ".", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(
                    temporary,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                properties.store(writer, "MINOS MCP backend configuration v1");
            }
            DurableAtomicFile.replace(temporary, file, "MCP backend configuration replacement");
            PrivateLocalStorage.verifyPrivateFile(file);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public Path file() {
        return file;
    }

    private void ensurePrivateRuntimeDirectory() throws IOException {
        PrivateLocalStorage.ensurePrivateDirectory(home);
        PrivateLocalStorage.ensurePrivateDirectory(file.getParent());
    }

    private void secureExistingConfigurationFile() throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("MCP backend configuration is not a real regular file: " + file);
        }
        PrivateLocalStorage.hardenExistingFile(file);
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing property " + key);
        }
        return value.trim();
    }
}
