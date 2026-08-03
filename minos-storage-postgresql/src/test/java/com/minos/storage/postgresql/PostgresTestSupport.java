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
 * Tests are skipped automatically when Docker is not reachable from the JVM
 * (e.g. Docker Desktop 4.30+ without the Testcontainers Desktop extension on Windows).
 * They run on Linux CI where Docker is always available.
 */
abstract class PostgresTestSupport {

    private static PostgreSQLContainer<?> POSTGRES;
    private static boolean dockerAvailable = false;

    @BeforeAll
    static void startPostgres() {
        try {
            POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:0.8.2-pg17")
                    .withDatabaseName("minos_test")
                    .withUsername("minos")
                    .withPassword("test-secret");
            POSTGRES.start();
            dockerAvailable = true;
        } catch (Exception e) {
            dockerAvailable = false;
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
        assumeTrue(dockerAvailable, "Docker not accessible from JVM — skipping PostgreSQL integration tests");
        connections = createFactory("minos");
        new PostgresSchemaMigrator(connections).migrate();
        truncateData();
    }

    PostgresConnectionFactory createFactory(String schema) throws IOException {
        Path home = Files.createTempDirectory("minos-pg-test-home");
        return new PostgresConnectionFactory(new StorageBackendConfiguration(
                "postgresql",
                home,
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                schema
        ));
    }

    private void truncateData() throws Exception {
        try (var c = connections.open(); var s = c.createStatement()) {
            s.execute("""
                TRUNCATE semantic_documents, semantic_index_meta,
                         runtime_sessions,
                         fingerprint_active, fingerprint_snapshots,
                         knowledge_active, knowledge_snapshots,
                         project_index_state, indexing_runs,
                         projects, workspaces
                CASCADE
            """);
        }
    }
}
