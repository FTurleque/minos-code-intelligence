package com.minos.storage.postgresql;

import com.minos.storage.StorageBackendConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Provider refusals that need no PostgreSQL server: the configuration is refused before any connection. */
class PostgresStorageBackendProviderTest {
    private static final String PASSWORD = "fixture-value";

    @Test
    void identifiesThePostgresqlBackend() {
        assertEquals("postgresql", new PostgresStorageBackendProvider().id());
    }

    @Test
    void refusesALocalConfiguration(@TempDir Path home) {
        StorageBackendConfiguration local = new StorageBackendConfiguration("local", home, null, null, null, null);
        PostgresStorageBackendProvider provider = new PostgresStorageBackendProvider();

        IOException failure = assertThrows(IOException.class, () -> provider.open(local));

        assertEquals("PostgreSQL configuration required", failure.getMessage());
    }

    @Test
    void refusesMissingCredentialsByNamingTheSetting(@TempDir Path home) {
        StorageBackendConfiguration noPassword = new StorageBackendConfiguration("postgresql", home,
                "jdbc:postgresql://127.0.0.1:5432/minos", "minos", null, null);
        PostgresStorageBackendProvider provider = new PostgresStorageBackendProvider();

        IOException failure = assertThrows(IOException.class, () -> provider.open(noPassword));

        assertTrue(failure.getMessage().contains("MINOS_POSTGRES_PASSWORD"), failure.getMessage());
    }

    @Test
    void refusesAnExternalHostWithoutVerifiedTlsBeforeSendingCredentials(@TempDir Path home) {
        StorageBackendConfiguration external = new StorageBackendConfiguration("postgresql", home,
                "jdbc:postgresql://db.example.invalid:5432/minos", "minos", PASSWORD, null);
        PostgresStorageBackendProvider provider = new PostgresStorageBackendProvider();

        IOException failure = assertThrows(IOException.class, () -> provider.open(external));

        assertEquals("external PostgreSQL requires sslmode=verify-full", failure.getMessage());
        assertFalse(failure.getMessage().contains(PASSWORD));
    }

    @Test
    void anUnreachableServerFailsOpeningWithoutLeakingTheSecret(@TempDir Path home) {
        // Port 1 of the loopback interface accepts no connection: the schema migration fails, and the provider
        // closes the pool it opened before rethrowing.
        StorageBackendConfiguration unreachable = new StorageBackendConfiguration("postgresql", home,
                "jdbc:postgresql://127.0.0.1:1/minos", "minos", PASSWORD, null);
        PostgresStorageBackendProvider provider = new PostgresStorageBackendProvider();

        IOException failure = assertThrows(IOException.class, () -> provider.open(unreachable));

        assertEquals("unable to initialize MINOS PostgreSQL backend", failure.getMessage());
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = String.valueOf(cause.getMessage());
            assertFalse(message.contains(PASSWORD), "the password leaked into " + cause);
        }
    }
}
