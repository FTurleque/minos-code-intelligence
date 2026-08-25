package com.minos.storage;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/** Storage selection and provider-neutral configuration resolved from durable MINOS settings. */
public record StorageBackendConfiguration(
        String backend,
        Path home,
        String postgresUrl,
        String postgresUser,
        String postgresPassword,
        String postgresSchema,
        boolean postgresManaged
) {
    public static final String BACKEND_ENV = "MINOS_STORAGE_BACKEND";
    public static final String BACKEND_PROPERTY = "minos.storage.backend";
    public static final String POSTGRES_URL_ENV = "MINOS_POSTGRES_URL";
    public static final String POSTGRES_URL_PROPERTY = "minos.postgres.url";
    public static final String POSTGRES_USER_ENV = "MINOS_POSTGRES_USER";
    public static final String POSTGRES_USER_PROPERTY = "minos.postgres.user";
    public static final String POSTGRES_PASSWORD_ENV = "MINOS_POSTGRES_PASSWORD";
    public static final String POSTGRES_PASSWORD_PROPERTY = "minos.postgres.password";
    public static final String POSTGRES_PASSWORD_FILE_ENV = "MINOS_POSTGRES_PASSWORD_FILE";
    public static final String POSTGRES_PASSWORD_FILE_PROPERTY = "minos.postgres.passwordFile";
    public static final String POSTGRES_SCHEMA_ENV = "MINOS_POSTGRES_SCHEMA";
    public static final String POSTGRES_SCHEMA_PROPERTY = "minos.postgres.schema";
    public static final String POSTGRES_MANAGED_ENV = "MINOS_POSTGRES_MANAGED";
    public static final String POSTGRES_MANAGED_PROPERTY = "minos.postgres.managed";
    private static final String REDACTED_POSTGRES_URL = "jdbc:postgresql:<redacted>";

    public StorageBackendConfiguration {
        backend = requireBackend(backend);
        home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        postgresSchema = normalizeSchema(postgresSchema);
    }

    /** Compatibility constructor for callers configuring an external PostgreSQL backend. */
    public StorageBackendConfiguration(
            String backend,
            Path home,
            String postgresUrl,
            String postgresUser,
            String postgresPassword,
            String postgresSchema
    ) {
        this(backend, home, postgresUrl, postgresUser, postgresPassword, postgresSchema, false);
    }

    public static StorageBackendConfiguration resolve(Path home) throws IOException {
        return resolve(MinosRuntimeSettings.load(home));
    }

    static StorageBackendConfiguration resolve(
            Path home,
            Map<String, String> environment,
            Properties properties
    ) throws IOException {
        return resolve(MinosRuntimeSettings.testing(home, new Properties(), environment, properties));
    }

    public static StorageBackendConfiguration resolve(MinosRuntimeSettings settings) throws IOException {
        Objects.requireNonNull(settings, "settings");
        String backend = settings.value(BACKEND_PROPERTY, BACKEND_ENV);
        if (backend == null || backend.isBlank()) backend = "local";
        String password = settings.secret(
                POSTGRES_PASSWORD_PROPERTY,
                POSTGRES_PASSWORD_ENV,
                POSTGRES_PASSWORD_FILE_PROPERTY,
                POSTGRES_PASSWORD_FILE_ENV
        );
        return new StorageBackendConfiguration(
                backend,
                settings.home(),
                settings.value(POSTGRES_URL_PROPERTY, POSTGRES_URL_ENV),
                settings.value(POSTGRES_USER_PROPERTY, POSTGRES_USER_ENV),
                password,
                settings.value(POSTGRES_SCHEMA_PROPERTY, POSTGRES_SCHEMA_ENV),
                parseBoolean(settings.value(POSTGRES_MANAGED_PROPERTY, POSTGRES_MANAGED_ENV), "PostgreSQL managed flag")
        );
    }

    public boolean postgresql() {
        return "postgresql".equals(backend);
    }

    private static String requireBackend(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("storage backend must not be blank");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("local") && !normalized.equals("postgresql")) {
            throw new IllegalArgumentException("unsupported storage backend: " + value);
        }
        return normalized;
    }

    private static String normalizeSchema(String value) {
        String normalized = value == null || value.isBlank() ? "minos" : value.trim();
        if (!normalized.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("PostgreSQL schema must be a safe SQL identifier");
        }
        return normalized;
    }

    private static boolean parseBoolean(String value, String label) {
        if (value == null || value.isBlank()) return false;
        if ("true".equalsIgnoreCase(value.trim())) return true;
        if ("false".equalsIgnoreCase(value.trim())) return false;
        throw new IllegalArgumentException(label + " must be true or false");
    }

    /** Safe diagnostic string excludes passwords, URL user-info and every JDBC query parameter. */
    public String safeDescription() {
        if (!postgresql()) return "backend=local home=" + home;
        return "backend=postgresql url=" + safePostgresUrl(postgresUrl)
                + " user=" + String.valueOf(postgresUser)
                + " schema=" + postgresSchema
                + " managed=" + postgresManaged;
    }

    static String safePostgresUrl(String value) {
        if (value == null || value.isBlank()) return String.valueOf(value);
        String raw = value.trim();
        if (!raw.startsWith("jdbc:postgresql://")) return REDACTED_POSTGRES_URL;
        try {
            URI uri = new URI(raw.substring("jdbc:".length()));
            String host = uri.getHost();
            if (host == null || host.isBlank()) return REDACTED_POSTGRES_URL;
            StringBuilder safe = new StringBuilder("jdbc:postgresql://");
            if (host.indexOf(':') >= 0) safe.append('[').append(host).append(']');
            else safe.append(host);
            if (uri.getPort() >= 0) safe.append(':').append(uri.getPort());
            String path = uri.getRawPath();
            if (path != null && !path.isBlank()) safe.append(path);
            return safe.toString();
        } catch (URISyntaxException exception) {
            return REDACTED_POSTGRES_URL;
        }
    }
}
