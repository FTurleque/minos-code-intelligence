package com.minos.storage.postgresql;

import com.minos.storage.StorageBackendConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

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
        return createFactory(schema, canonicalJdbcUrl());
    }

    /** Factory bound to {@code database} on the shared container instead of the default test database. */
    PostgresConnectionFactory createFactoryForDatabase(String database, String schema) throws IOException {
        return createFactory(schema, jdbcUrlForDatabase(database));
    }

    private PostgresConnectionFactory createFactory(String schema, String jdbcUrl) throws IOException {
        Path home = Files.createTempDirectory("minos-pg-test-home");
        return new PostgresConnectionFactory(new StorageBackendConfiguration(
                "postgresql",
                home,
                jdbcUrl,
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                schema
        ));
    }

    private static String canonicalJdbcUrl() {
        String containerJdbcUrl = POSTGRES.getJdbcUrl();
        int queryStart = containerJdbcUrl.indexOf('?');
        return queryStart < 0 ? containerJdbcUrl : containerJdbcUrl.substring(0, queryStart);
    }

    private static String jdbcUrlForDatabase(String database) {
        String canonical = canonicalJdbcUrl();
        return canonical.substring(0, canonical.lastIndexOf('/') + 1) + database;
    }

    /**
     * Creates an empty database on the shared container. A test that must exercise database-wide
     * bootstrap behaviour (for example the {@code CREATE EXTENSION} race) needs a database where
     * that state genuinely does not exist yet; the default test database has already been migrated
     * by {@link #setUpSchema()}, so {@code IF NOT EXISTS} would short-circuit the very step under test.
     */
    void createFreshDatabase(String database) throws SQLException {
        try (Connection admin = DriverManager.getConnection(
                     canonicalJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = admin.createStatement()) {
            String quoted = statement.enquoteIdentifier(database, true);
            statement.execute("DROP DATABASE IF EXISTS " + quoted);
            statement.execute("CREATE DATABASE " + quoted);
        }
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
