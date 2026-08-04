package com.minos.storage.postgresql;

import com.minos.storage.StorageBackendConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

class PostgresConnectionFactoryTest {

    private static final String VALID_URL = "jdbc:postgresql://localhost:5432/minos_test";

    @Test
    void rejectsEmptyUrl(@TempDir Path home) {
        assertThrows(IOException.class,
                () -> factory(home, "", "minos", "secret", "minos"));
    }

    @Test
    void rejectsNonPostgresUrl(@TempDir Path home) {
        assertThrows(IOException.class,
                () -> factory(home, "jdbc:h2:mem:test", "minos", "secret", "minos"));
    }

    @Test
    void rejectsEmptyUser(@TempDir Path home) {
        assertThrows(IOException.class,
                () -> factory(home, VALID_URL, "", "secret", "minos"));
    }

    @Test
    void rejectsEmptyPassword(@TempDir Path home) {
        assertThrows(IOException.class,
                () -> factory(home, VALID_URL, "minos", "", "minos"));
    }

    @Test
    void rejectsBlankPassword(@TempDir Path home) {
        assertThrows(IOException.class,
                () -> factory(home, VALID_URL, "minos", "   ", "minos"));
    }

    private static PostgresConnectionFactory factory(
            Path home, String url, String user, String password, String schema) throws IOException {
        return new PostgresConnectionFactory(
                new StorageBackendConfiguration("postgresql", home, url, user, password, schema));
    }
}
