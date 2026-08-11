package com.minos.storage.postgresql;

import com.minos.storage.StorageBackendConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Base class for PostgreSQL integration tests.
 *
 * <p>Developer workstations may skip these tests when Docker is unavailable. CI and
 * release qualification set {@code -Dminos.postgresql.tests.required=true}, which
 * turns an unavailable Docker/PostgreSQL runtime into a hard failure so the storage
 * gate can never disappear silently.</p>
 */
abstract class PostgresTestSupport {

    static final String REQUIRED_PROPERTY = "minos.postgresql.tests.required";

    private static PostgreSQLContainer<?> POSTGRES;
    private static boolean dockerAvailable = false;
    private static Throwable dockerFailure;

    @BeforeAll
    static void startPostgres() {
        try {
            POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.2-pg17")
                    .withDatabaseName("minos_test")
                    .withUsername("minos")
                    .withPassword("test-secret");
            POSTGRES.start();
            dockerAvailable = true;
            dockerFailure = null;
        } catch (Exception exception) {
            dockerAvailable = false;
            dockerFailure = exception;
            if (Boolean.getBoolean(REQUIRED_PROPERTY)) {
                throw new IllegalStateException(
                        "PostgreSQL integration tests are required but Docker/pgvector could not start",
                        exception
                );
            }
        }
    }

    @AfterAll
    static void stopPostgres() {
        if (POSTGRES != null && POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    PostgresConnectionFactory connections;

    @BeforeEach
    void setUpSchema() throws Exception {
        String reason = "Docker not accessible from JVM — skipping PostgreSQL integration tests";
        if (dockerFailure != null && dockerFailure.getMessage() != null) {
            reason += ": " + dockerFailure.getMessage();
        }
        assumeTrue(dockerAvailable, reason);
        connections = createFactory("minos");
        new PostgresSchemaMigrator(connections).migrate();
        truncateData();
    }

    PostgresConnectionFactory createFactory(String schema) throws IOException {
        Path home = Files.createTempDirectory("minos-pg-test-home");
        String containerJdbcUrl = POSTGRES.getJdbcUrl();
        int queryStart = containerJdbcUrl.indexOf('?');
        String canonicalJdbcUrl = queryStart < 0
                ? containerJdbcUrl
                : containerJdbcUrl.substring(0, queryStart);
        return new PostgresConnectionFactory(new StorageBackendConfiguration(
                "postgresql",
                home,
                canonicalJdbcUrl,
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                schema
        ));
    }

    private void truncateData() throws Exception {
        connections.withConnection(c -> {
            try (var s = c.createStatement()) {
                s.execute("""
                    TRUNCATE semantic_documents, semantic_index_meta,
                             runtime_sessions,
                             fingerprint_active, fingerprint_snapshots,
                             knowledge_active, knowledge_snapshots,
                             project_index_state, indexing_runs,
                             projects, workspaces
                    CASCADE
                """);
                return null;
            }
        });
    }
}
