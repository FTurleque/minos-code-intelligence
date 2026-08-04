package com.minos.storage;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageBackendConfigurationTest {

    @Test
    void defaultsToHistoricalLocalBackend() throws IOException {
        StorageBackendConfiguration value = StorageBackendConfiguration.resolve(
                Path.of("target/test-minos-home"), Map.of(), new Properties());
        assertEquals("local", value.backend());
        assertFalse(value.postgresql());
        assertEquals("minos", value.postgresSchema());
    }

    @Test
    void resolvesPostgresqlWithoutLeakingPasswordInDiagnostics() throws IOException {
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
    void resolvesPasswordFromFileWithoutPuttingItInMainConfiguration() throws IOException {
        Path home = Files.createTempDirectory("minos-storage-settings-");
        Path secret = home.resolve("secrets/postgres.password");
        Files.createDirectories(secret.getParent());
        Files.writeString(secret, "file-secret\n");
        Properties file = new Properties();
        file.setProperty(StorageBackendConfiguration.BACKEND_PROPERTY, "postgresql");
        file.setProperty(StorageBackendConfiguration.POSTGRES_PASSWORD_FILE_PROPERTY, "secrets/postgres.password");
        MinosRuntimeSettings settings = MinosRuntimeSettings.testing(home, file, Map.of(), new Properties());

        StorageBackendConfiguration value = StorageBackendConfiguration.resolve(settings);

        assertEquals("file-secret", value.postgresPassword());
        assertFalse(value.safeDescription().contains("file-secret"));
    }

    @Test
    void rejectsUnsafeSqlSchemaIdentifiers() {
        Properties properties = new Properties();
        properties.setProperty(StorageBackendConfiguration.POSTGRES_SCHEMA_PROPERTY, "minos;drop schema public");
        assertThrows(IllegalArgumentException.class, () -> StorageBackendConfiguration.resolve(
                Path.of("target/test-minos-home"), Map.of(), properties));
    }
}
