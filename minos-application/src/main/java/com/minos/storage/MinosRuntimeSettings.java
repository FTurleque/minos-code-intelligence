package com.minos.storage;

import com.minos.io.BoundedInputStream;
import com.minos.io.BoundedProperties;
import com.minos.io.ConfinedFileOpener;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
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
 * <p>The resolved values remain scoped to this settings instance. Loading one MINOS home never
 * mutates JVM-global system properties and therefore cannot contaminate another application
 * instance in the same JVM.</p>
 *
 * <p>Secret values are never required in the properties file. Password-file indirection is
 * supported so installers can ACL the secret independently from the human-readable config.
 * Relative secret paths are confined to the physical MINOS home even when symlinks are involved;
 * absolute secret paths remain an explicit operator escape hatch for mounted secret stores.</p>
 */
public final class MinosRuntimeSettings {
    public static final String CONFIG_DIRECTORY = "config";
    public static final String CONFIG_FILE = "minos.properties";

    private static final long MAX_CONFIGURATION_BYTES = 256L * 1024L;
    private static final int MAX_CONFIGURATION_ENTRIES = 512;
    private static final int MAX_CONFIGURATION_KEY_CHARS = 256;
    private static final int MAX_CONFIGURATION_VALUE_CHARS = 16 * 1024;
    private static final long MAX_SECRET_BYTES = 64L * 1024L;

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
            file.putAll(BoundedProperties.load(
                    configuration,
                    MAX_CONFIGURATION_BYTES,
                    MAX_CONFIGURATION_ENTRIES,
                    MAX_CONFIGURATION_KEY_CHARS,
                    MAX_CONFIGURATION_VALUE_CHARS,
                    "MINOS runtime configuration"
            ));
        }
        Properties systemSnapshot = new Properties();
        systemSnapshot.putAll(System.getProperties());
        return new MinosRuntimeSettings(normalized, file, Map.copyOf(System.getenv()), systemSnapshot);
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

        Path configuredSecretPath = Path.of(configuredPath);
        String secret = configuredSecretPath.isAbsolute()
                ? readAbsoluteSecret(configuredSecretPath.normalize())
                : readConfinedRelativeSecret(configuredSecretPath);
        secret = secret.trim();
        if (secret.isEmpty()) {
            throw new IOException("configured MINOS secret file is empty: " + configuredSecretPath);
        }
        return secret;
    }

    private String readAbsoluteSecret(Path secretPath) throws IOException {
        if (!Files.isRegularFile(secretPath)) {
            throw new IOException("configured MINOS secret file does not exist: " + secretPath);
        }
        return BoundedProperties.readUtf8(secretPath, MAX_SECRET_BYTES, "MINOS secret file");
    }

    private String readConfinedRelativeSecret(Path configuredSecretPath) throws IOException {
        Path candidate = home.resolve(configuredSecretPath).normalize();
        if (!candidate.startsWith(home)) {
            throw new IOException("relative MINOS secret file must stay inside MINOS_HOME: " + configuredSecretPath);
        }
        if (!Files.isRegularFile(candidate)) {
            throw new IOException("configured MINOS secret file does not exist: " + candidate);
        }

        // Preserve the explicit diagnostic for a statically visible symlink escape, but do not rely
        // on this pathname check for the actual read: ConfinedFileOpener binds the read to the
        // physically traversed object and prevents a validate-then-reopen race on supported hosts.
        Path physicalHome = home.toRealPath();
        Path physicalSecret = candidate.toRealPath();
        if (!physicalSecret.startsWith(physicalHome)) {
            throw new IOException("relative MINOS secret file escapes MINOS_HOME through a symbolic link: "
                    + configuredSecretPath);
        }

        Path relative = home.relativize(candidate);
        try (SeekableByteChannel channel = ConfinedFileOpener.openConfinedRegularFile(home, relative);
             InputStream stream = Channels.newInputStream(channel);
             BoundedInputStream input = new BoundedInputStream(stream, MAX_SECRET_BYTES, "MINOS secret file")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (ConfinedFileOpener.ConfinementException exception) {
            throw new IOException("relative MINOS secret file failed physical confinement: "
                    + configuredSecretPath, exception);
        }
    }

    public Path home() {
        return home;
    }

    public Path configurationFile() {
        return home.resolve(CONFIG_DIRECTORY).resolve(CONFIG_FILE);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
