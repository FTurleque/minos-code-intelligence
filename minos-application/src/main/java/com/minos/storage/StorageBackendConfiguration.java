package com.minos.storage;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/** Storage selection and provider-neutral configuration resolved from properties/environment. */
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
    public static final String POSTGRES_SCHEMA_ENV = "MINOS_POSTGRES_SCHEMA";
    public static final String POSTGRES_SCHEMA_PROPERTY = "minos.postgres.schema";

    public StorageBackendConfiguration {
        backend = requireBackend(backend);
        home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        postgresSchema = normalizeSchema(postgresSchema);
    }

    public static StorageBackendConfiguration resolve(Path home) {
        return resolve(home, System.getenv(), System.getProperties());
    }

    static StorageBackendConfiguration resolve(Path home, Map<String, String> environment, Properties properties) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(properties, "properties");
        String backend = setting(properties, environment, BACKEND_PROPERTY, BACKEND_ENV);
        if (backend == null || backend.isBlank()) backend = "local";
        return new StorageBackendConfiguration(
                backend,
                home,
                setting(properties, environment, POSTGRES_URL_PROPERTY, POSTGRES_URL_ENV),
                setting(properties, environment, POSTGRES_USER_PROPERTY, POSTGRES_USER_ENV),
                setting(properties, environment, POSTGRES_PASSWORD_PROPERTY, POSTGRES_PASSWORD_ENV),
                setting(properties, environment, POSTGRES_SCHEMA_PROPERTY, POSTGRES_SCHEMA_ENV)
        );
    }

    public boolean postgresql() {
        return "postgresql".equals(backend);
    }

    private static String setting(Properties properties, Map<String, String> environment, String property, String env) {
        String value = properties.getProperty(property);
        if (value == null || value.isBlank()) value = environment.get(env);
        return value == null || value.isBlank() ? null : value.trim();
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
