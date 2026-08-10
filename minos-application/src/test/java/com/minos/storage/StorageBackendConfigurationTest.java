package com.minos.storage;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertFalse(value.postgresManaged());
    }

    @Test
    void resolvesPostgresqlWithoutLeakingPasswordInDiagnostics() throws IOException {
        Properties properties = new Properties();
        properties.setProperty(StorageBackendConfiguration.BACKEND_PROPERTY, "postgresql");
        properties.setProperty(StorageBackendConfiguration.POSTGRES_URL_PROPERTY,
                "jdbc:postgresql://localhost:5432/minos?sslmode=verify-full&token=url-secret");
        properties.setProperty(StorageBackendConfiguration.POSTGRES_USER_PROPERTY, "minos_user");
        properties.setProperty(StorageBackendConfiguration.POSTGRES_PASSWORD_PROPERTY, "super-secret-value");
        properties.setProperty(StorageBackendConfiguration.POSTGRES_SCHEMA_PROPERTY, "minos_ci");

        StorageBackendConfiguration value = StorageBackendConfiguration.resolve(
                Path.of("target/test-minos-home"), Map.of(), properties);

        assertTrue(value.postgresql());
        assertEquals("minos_ci", value.postgresSchema());
        assertFalse(value.safeDescription().contains("super-secret-value"));
        assertFalse(value.safeDescription().contains("url-secret"));
        assertFalse(value.safeDescription().contains("sslmode"));
        assertEquals(
                "backend=postgresql url=jdbc:postgresql://localhost:5432/minos user=minos_user schema=minos_ci managed=false",
                value.safeDescription());
    }

    @Test
    void resolvesManagedPostgresqlFlagStrictly() throws IOException {
        Properties properties = new Properties();
        properties.setProperty(StorageBackendConfiguration.BACKEND_PROPERTY, "postgresql");
        properties.setProperty(StorageBackendConfiguration.POSTGRES_MANAGED_PROPERTY, "true");
        StorageBackendConfiguration value = StorageBackendConfiguration.resolve(
                Path.of("target/test-minos-home"), Map.of(), properties);
        assertTrue(value.postgresManaged());

        properties.setProperty(StorageBackendConfiguration.POSTGRES_MANAGED_PROPERTY, "sometimes");
        assertThrows(IllegalArgumentException.class, () -> StorageBackendConfiguration.resolve(
                Path.of("target/test-minos-home"), Map.of(), properties));
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
    void fileSettingsRemainScopedToEachHomeAndNeverMutateJvmProperties() throws IOException {
        String property = "minos.test.homeScopedSetting";
        String environment = "MINOS_TEST_HOME_SCOPED_SETTING";
        String previous = System.getProperty(property);
        try {
            System.clearProperty(property);
            Path firstHome = Files.createTempDirectory("minos-settings-home-a-");
            Path secondHome = Files.createTempDirectory("minos-settings-home-b-");
            writeSetting(firstHome, property, "alpha");
            writeSetting(secondHome, property, "beta");

            MinosRuntimeSettings first = MinosRuntimeSettings.load(firstHome);
            MinosRuntimeSettings second = MinosRuntimeSettings.load(secondHome);

            assertEquals("alpha", first.value(property, environment));
            assertEquals("beta", second.value(property, environment));
            assertNull(System.getProperty(property), "loading file settings must not mutate JVM-global state");
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }

    @Test
    void rejectsUnsafeSqlSchemaIdentifiers() {
        Properties properties = new Properties();
        properties.setProperty(StorageBackendConfiguration.POSTGRES_SCHEMA_PROPERTY, "minos;drop schema public");
        assertThrows(IllegalArgumentException.class, () -> StorageBackendConfiguration.resolve(
                Path.of("target/test-minos-home"), Map.of(), properties));
    }

    private static void writeSetting(Path home, String property, String value) throws IOException {
        Path config = home.resolve(MinosRuntimeSettings.CONFIG_DIRECTORY).resolve(MinosRuntimeSettings.CONFIG_FILE);
        Files.createDirectories(config.getParent());
        Files.writeString(config, property + "=" + value + "\n");
    }
}
