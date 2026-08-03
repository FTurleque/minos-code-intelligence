package com.minos.storage;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Durable MINOS runtime configuration resolved with explicit precedence:
 * JVM property &gt; environment &gt; {@code MINOS_HOME/config/minos.properties}.
 *
 * <p>Secret values are never required in the properties file. Password-file indirection is
 * supported so installers can ACL the secret independently from the human-readable config.</p>
 */
public final class MinosRuntimeSettings {
    public static final String CONFIG_DIRECTORY = "config";
    public static final String CONFIG_FILE = "minos.properties";

    private final Path home;
    private final Properties fileProperties;
    private final Map<String, String> environment;
    private final Properties systemProperties;

    private MinosRuntimeSettings(
            Path home,
            Properties fileProperties,
            Map<String, String> environment,
            Properties systemProperties
    ) {
        this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        this.fileProperties = Objects.requireNonNull(fileProperties, "fileProperties");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.systemProperties = Objects.requireNonNull(systemProperties, "systemProperties");
    }

    public static MinosRuntimeSettings load(Path home) throws IOException {
        Path normalized = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        Properties file = new Properties();
        Path configuration = normalized.resolve(CONFIG_DIRECTORY).resolve(CONFIG_FILE);
        if (Files.isRegularFile(configuration)) {
            try (Reader reader = Files.newBufferedReader(configuration, StandardCharsets.UTF_8)) {
                file.load(reader);
            }
        }
        return new MinosRuntimeSettings(normalized, file, System.getenv(), System.getProperties());
    }

    static MinosRuntimeSettings testing(
            Path home,
            Properties fileProperties,
            Map<String, String> environment,
            Properties systemProperties
    ) {
        Properties copy = new Properties();
        copy.putAll(fileProperties);
        Properties systemCopy = new Properties();
        systemCopy.putAll(systemProperties);
        return new MinosRuntimeSettings(home, copy, Map.copyOf(environment), systemCopy);
    }

    public String value(String property, String environmentVariable) {
        String value = systemProperties.getProperty(property);
        if (blank(value)) value = environment.get(environmentVariable);
        if (blank(value)) value = fileProperties.getProperty(property);
        return blank(value) ? null : value.trim();
    }

    /**
     * Makes a durable file value visible to legacy property/environment readers without
     * overriding an explicit JVM property or environment variable. Secrets must never use
     * this mechanism.
     */
    public void activateFileFallback(String property, String environmentVariable) {
        if (!blank(systemProperties.getProperty(property))) return;
        if (!blank(environment.get(environmentVariable))) return;
        String configured = fileProperties.getProperty(property);
        if (blank(configured)) return;
        System.setProperty(property, configured.trim());
        systemProperties.setProperty(property, configured.trim());
    }

    public String secret(
            String valueProperty,
            String valueEnvironment,
            String fileProperty,
            String fileEnvironment
    ) throws IOException {
        String direct = value(valueProperty, valueEnvironment);
        if (!blank(direct)) return direct;
        String configuredPath = value(fileProperty, fileEnvironment);
        if (blank(configuredPath)) return null;
        Path secretPath = Path.of(configuredPath);
        if (!secretPath.isAbsolute()) secretPath = home.resolve(secretPath);
        secretPath = secretPath.normalize();
        if (!Files.isRegularFile(secretPath)) {
            throw new IOException("configured MINOS secret file does not exist: " + secretPath);
        }
        String secret = Files.readString(secretPath, StandardCharsets.UTF_8).trim();
        if (secret.isEmpty()) throw new IOException("configured MINOS secret file is empty: " + secretPath);
        return secret;
    }

    public Path home() { return home; }
    public Path configurationFile() { return home.resolve(CONFIG_DIRECTORY).resolve(CONFIG_FILE); }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
