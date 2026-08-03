package com.minos.storage;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageBackendConfigurationTest {

    @Test
    void defaultsToHistoricalLocalBackend() {
        StorageBackendConfiguration value = StorageBackendConfiguration.resolve(
                Path.of("target/test-minos-home"), Map.of(), new Properties());
        assertEquals("local", value.backend());
        assertFalse(value.postgresql());
        assertEquals("minos", value.postgresSchema());
    }

    @Test
    void resolvesPostgresqlWithoutLeakingPasswordInDiagnostics() {
        Properties properties = new Properties();
        properties.setProperty(StorageBackendConfiguration.BACKEND_PROPERTY, "postgresql");
        properties.setProperty(StorageBackendConfiguration.POSTGRES_URL_PROPERTY, "jdbc:postgresql://localhost:5432/minos");
        properties.setProperty(StorageBackendConfiguration.POSTGRES_USER_PROPERTY, "minos_user");
        properties.setProperty(StorageBackendConfiguration.POSTGRES_PASSWORD_PROPERTY, "super-secret-value");
        properties.setProperty(StorageBackendConfiguration.POSTGRES_SCHEMA_PROPERTY, "minos_ci");

        StorageBackendConfiguration value = StorageBackendConfiguration.resolve(
                Path.of("target/test-minos-home"), Map.of(), properties);

        assertTrue(value.postgresql());
        assertEquals("minos_ci", value.postgresSchema());
        assertFalse(value.safeDescription().contains("super-secret-value"));
        assertTrue(value.safeDescription().contains("jdbc:postgresql://localhost:5432/minos"));
    }

    @Test
    void rejectsUnsafeSqlSchemaIdentifiers() {
        Properties properties = new Properties();
        properties.setProperty(StorageBackendConfiguration.POSTGRES_SCHEMA_PROPERTY, "minos;drop schema public");
        assertThrows(IllegalArgumentException.class, () -> StorageBackendConfiguration.resolve(
                Path.of("target/test-minos-home"), Map.of(), properties));
    }
}
