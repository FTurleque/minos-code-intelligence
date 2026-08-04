package com.minos.storage;

import java.io.IOException;
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
        String postgresSchema
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

    public StorageBackendConfiguration {
        backend = requireBackend(backend);
        home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        postgresSchema = normalizeSchema(postgresSchema);
    }

    public static StorageBackendConfiguration resolve(Path home) throws IOException {
        MinosRuntimeSettings settings = MinosRuntimeSettings.load(home);
        activateNonSecretRuntimeFallbacks(settings);
        return resolve(settings);
    }

    static StorageBackendConfiguration resolve(Path home, Map<String, String> environment, Properties properties)
            throws IOException {
        return resolve(MinosRuntimeSettings.testing(home, new Properties(), environment, properties));
    }

    static StorageBackendConfiguration resolve(MinosRuntimeSettings settings) throws IOException {
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
                settings.value(POSTGRES_SCHEMA_PROPERTY, POSTGRES_SCHEMA_ENV)
        );
    }

    public boolean postgresql() { return "postgresql".equals(backend); }

    private static void activateNonSecretRuntimeFallbacks(MinosRuntimeSettings settings) {
        settings.activateFileFallback("minos.semantic.provider", "MINOS_SEMANTIC_PROVIDER");
        settings.activateFileFallback("minos.semantic.model", "MINOS_SEMANTIC_MODEL");
        settings.activateFileFallback("minos.semantic.dimensions", "MINOS_SEMANTIC_DIMENSIONS");
        settings.activateFileFallback("minos.semantic.endpoint", "MINOS_SEMANTIC_ENDPOINT");
        settings.activateFileFallback("minos.semantic.timeoutSeconds", "MINOS_SEMANTIC_TIMEOUT_SECONDS");
        settings.activateFileFallback("minos.hosted.mode", "MINOS_HOSTED_MODE");
    }

    private static String requireBackend(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("storage backend must not be blank");
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

    /** Safe diagnostic string intentionally excludes the PostgreSQL password. */
    public String safeDescription() {
        if (!postgresql()) return "backend=local home=" + home;
        return "backend=postgresql url=" + String.valueOf(postgresUrl)
                + " user=" + String.valueOf(postgresUser) + " schema=" + postgresSchema;
    }
}
