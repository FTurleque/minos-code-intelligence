package com.minos.storage.postgresql;

import com.minos.storage.postgresql.support.PostgresConnectionFactory;
import com.minos.storage.postgresql.support.PostgresProjectMutationLock;
import com.minos.storage.postgresql.support.PostgresSchemaMigrator;
import com.minos.storage.StorageBackendConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Base class for PostgreSQL integration tests.
 *
 * <p>Developer workstations may skip these tests when Docker is unavailable. CI and
 * release qualification set {@code -Dminos.postgresql.tests.required=true}, which
 * turns an unavailable Docker/PostgreSQL runtime into a hard failure so the storage
 * gate can never disappear silently.</p>
 */
public abstract class PostgresTestSupport {

    protected static final String REQUIRED_PROPERTY = "minos.postgresql.tests.required";

    private static PostgreSQLContainer POSTGRES;
    private static boolean dockerAvailable = false;
    private static Throwable dockerFailure;

    @BeforeAll
    protected static void startPostgres() throws IOException {
        // Outside the try: a missing or malformed pin is a repository defect, never a skipped suite.
        DockerImageName image = DockerImageName.parse(pinnedPostgresImage()).asCompatibleSubstituteFor("postgres");
        try {
            POSTGRES = new PostgreSQLContainer(image)
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

    /**
     * The pgvector image the managed Docker runtime ships, read from its single source: the default
     * of {@code MINOS_POSTGRES_IMAGE} in {@code docker/compose-mcp.connected.yaml}. The tests then
     * exercise the exact tag-and-digest reference users run, and no second copy of it exists.
     */
    protected static String pinnedPostgresImage() throws IOException {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path compose = directory.resolve("docker").resolve("compose-mcp.connected.yaml");
            if (Files.isRegularFile(compose)) {
                Matcher image = Pattern.compile("MINOS_POSTGRES_IMAGE:-([^}\"\\s]+)")
                        .matcher(Files.readString(compose));
                if (!image.find()) {
                    throw new IllegalStateException("compose-mcp.connected.yaml declares no MINOS_POSTGRES_IMAGE default");
                }
                return image.group(1);
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("docker/compose-mcp.connected.yaml not found above the module directory");
    }

    @AfterAll
    protected static void stopPostgres() {
        if (POSTGRES != null && POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    protected PostgresConnectionFactory connections;

    @BeforeEach
    protected void setUpSchema() throws Exception {
        String reason = "Docker not accessible from JVM — skipping PostgreSQL integration tests";
        if (dockerFailure != null && dockerFailure.getMessage() != null) {
            reason += ": " + dockerFailure.getMessage();
        }
        assumeTrue(dockerAvailable, reason);
        connections = createFactory("minos");
        new PostgresSchemaMigrator(connections).migrate();
        truncateData();
    }

    protected PostgresConnectionFactory createFactory(String schema) throws IOException {
        return createFactory(schema, canonicalJdbcUrl());
    }

    /** Factory bound to {@code database} on the shared container instead of the default test database. */
    protected PostgresConnectionFactory createFactoryForDatabase(String database, String schema) throws IOException {
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
    protected void createFreshDatabase(String database) throws SQLException {
        try (Connection admin = DriverManager.getConnection(
                     canonicalJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = admin.createStatement()) {
            String quoted = statement.enquoteIdentifier(database, true);
            statement.execute("DROP DATABASE IF EXISTS " + quoted);
            statement.execute("CREATE DATABASE " + quoted);
        }
    }

    /**
     * Waits until a session of the test database is waiting for, and not yet granted, the project mutation
     * advisory lock of {@code projectId} (AUD-TST-10). This is the direct proof that a concurrent writer is
     * blocked by the lock: {@code pg_locks} lists the request as {@code granted = false}. The query filters on
     * the lock key ({@link PostgresProjectMutationLock#key}) and on the current database, so a lock held or
     * awaited for another project is ignored. The polling connection is opened outside the pool under test.
     * When no such waiter appears before {@code timeout}, the test fails with a message.
     */
    protected static void awaitAdvisoryLockWaiter(UUID projectId, Duration timeout) throws SQLException {
        long key = PostgresProjectMutationLock.key(projectId);
        long deadline = System.nanoTime() + timeout.toNanos();
        try (Connection observer = DriverManager.getConnection(
                     canonicalJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement waiters = observer.prepareStatement("""
                     SELECT count(*) FROM pg_locks
                     WHERE locktype = 'advisory' AND NOT granted AND objsubid = 1
                       AND database = (SELECT oid FROM pg_database WHERE datname = current_database())
                       AND ((classid::bigint << 32) | objid::bigint) = ?
                     """)) {
            waiters.setLong(1, key);
            while (true) {
                try (ResultSet result = waiters.executeQuery()) {
                    result.next();
                    if (result.getLong(1) > 0L) return;
                }
                if (System.nanoTime() - deadline >= 0L) {
                    fail("no session was observed waiting for the advisory lock of project " + projectId
                            + " within " + timeout);
                }
            }
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
