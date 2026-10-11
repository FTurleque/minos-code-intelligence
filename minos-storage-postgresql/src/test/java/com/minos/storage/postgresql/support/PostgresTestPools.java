package com.minos.storage.postgresql.support;

import com.minos.storage.StorageBackendConfiguration;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;

/**
 * Test-only access to the bounded-pool constructor, so a test of another package can exercise an acquisition
 * timeout in milliseconds instead of waiting for the ten-second production default.
 */
public final class PostgresTestPools {
    private PostgresTestPools() {
    }

    /**
     * A second pool on the server {@code source} is connected to, with its own size and acquire timeout. The
     * password is the fixture credential the test container is created with in {@code PostgresTestSupport}.
     */
    public static PostgresConnectionFactory sized(
            PostgresConnectionFactory source, Path home, int maximumSize, Duration acquireTimeout
    ) throws SQLException, IOException {
        String url = source.withConnection(connection -> connection.getMetaData().getURL());
        String user = source.withConnection(connection -> connection.getMetaData().getUserName());
        StorageBackendConfiguration configuration = new StorageBackendConfiguration(
                "postgresql", home, url, user, "test-secret", source.schema());
        return new PostgresConnectionFactory(configuration, maximumSize, acquireTimeout, 10, 120);
    }
}
