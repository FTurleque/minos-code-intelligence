package com.minos.storage.postgresql;

import com.minos.storage.postgresql.support.PostgresConnectionFactory;
import com.minos.storage.postgresql.support.PostgresProjectMutationLock;
import com.minos.storage.postgresql.support.PostgresSchemaMigrator;
import com.minos.storage.postgresql.support.PostgresTestServer;
import com.minos.storage.StorageBackendConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Base class for PostgreSQL integration tests.
 *
 * <p>The server is started outside the test JVM, by {@code scripts/dev/start-test-postgres.sh} (or {@code .ps1})
 * locally and in CI, and designated by {@link PostgresTestServer}: no test starts a container or talks to a Docker
 * daemon. Each test class works in its own database, created before its first test and dropped after its last, so
 * no result depends on the order of the classes or on the data of another class.</p>
 *
 * <p>Developer workstations without a server skip these tests (counted as skipped, with the reason). CI and
 * release qualification set {@code -Dminos.postgresql.tests.required=true}, which turns a missing, unreachable or
 * refusing server into a hard failure so the storage gate can never disappear silently.</p>
 */
public abstract class PostgresTestSupport {

    protected static final String REQUIRED_PROPERTY = "minos.postgresql.tests.required";

    private static final String DATABASE_PREFIX = "minos_t_";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int CONNECT_TIMEOUT_SECONDS = 10;

    private static PostgresTestServer server;
    private static String classDatabase;
    private static final List<String> extraDatabases = new ArrayList<>();
    private static boolean serverAvailable = false;
    private static String unavailableReason;

    @BeforeAll
    protected static void startPostgres() {
        Optional<String> skip = prepareServer(System::getProperty, System::getenv, Boolean.getBoolean(REQUIRED_PROPERTY));
        serverAvailable = skip.isEmpty();
        unavailableReason = skip.orElse(null);
    }

    /**
     * Resolves the server from {@code properties}, then {@code environment}, and creates the database of the class.
     * Returns the reason to skip when the server cannot be used; in {@code required} mode that reason is an
     * {@link IllegalStateException} instead. No message carries the password.
     */
    static Optional<String> prepareServer(UnaryOperator<String> properties, UnaryOperator<String> environment,
                                          boolean required) {
        String reason;
        Throwable cause = null;
        try {
            PostgresTestServer resolved = PostgresTestServer.resolve(properties, environment);
            String database = newDatabaseName();
            try (Connection admin = openAdmin(resolved); Statement statement = admin.createStatement()) {
                statement.execute("CREATE DATABASE " + statement.enquoteIdentifier(database, true));
            }
            server = resolved;
            classDatabase = database;
            return Optional.empty();
        } catch (PostgresTestServer.MissingParameterException missing) {
            reason = missing.getMessage();
        } catch (SQLException failure) {
            reason = describe(failure, properties, environment);
            cause = failure;
        }
        if (required) {
            throw new IllegalStateException("PostgreSQL integration tests are required but " + reason, cause);
        }
        return Optional.of(reason);
    }

    /** A database name unique to one class and one run, recognisable by its prefix if a killed JVM leaves it. */
    static String newDatabaseName() {
        byte[] bytes = new byte[6];
        RANDOM.nextBytes(bytes);
        return DATABASE_PREFIX + HexFormat.of().formatHex(bytes);
    }

    @AfterAll
    protected static void stopPostgres() {
        if (server == null) {
            return;
        }
        List<String> databases = new ArrayList<>(extraDatabases);
        if (classDatabase != null) {
            databases.add(classDatabase);
        }
        try (Connection admin = openAdmin(server); Statement statement = admin.createStatement()) {
            for (String database : databases) {
                // FORCE (PostgreSQL 13+) closes the pool connections a test left open.
                statement.execute("DROP DATABASE IF EXISTS " + statement.enquoteIdentifier(database, true)
                        + " WITH (FORCE)");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("the databases of the class could not be dropped: " + databases, failure);
        } finally {
            extraDatabases.clear();
            classDatabase = null;
            server = null;
            serverAvailable = false;
        }
    }

    protected PostgresConnectionFactory connections;

    @BeforeEach
    protected void setUpSchema() throws Exception {
        String reason = "PostgreSQL test server not available — skipping PostgreSQL integration tests";
        if (unavailableReason != null) {
            reason += ": " + unavailableReason;
        }
        assumeTrue(serverAvailable, reason);
        connections = createFactory("minos");
        new PostgresSchemaMigrator(connections).migrate();
        truncateData();
    }

    protected PostgresConnectionFactory createFactory(String schema) throws IOException {
        return createFactory(schema, server.urlForDatabase(classDatabase));
    }

    /** Factory bound to {@code database} on the test server instead of the database of the class. */
    protected PostgresConnectionFactory createFactoryForDatabase(String database, String schema) throws IOException {
        return createFactory(schema, server.urlForDatabase(database));
    }

    private PostgresConnectionFactory createFactory(String schema, String jdbcUrl) throws IOException {
        Path home = Files.createTempDirectory("minos-pg-test-home");
        return new PostgresConnectionFactory(new StorageBackendConfiguration(
                "postgresql",
                home,
                jdbcUrl,
                server.user(),
                server.password(),
                schema
        ));
    }

    /**
     * Creates an empty database on the test server, dropped with the database of the class. A test that must
     * exercise database-wide bootstrap behaviour (for example the {@code CREATE EXTENSION} race) needs a database
     * where that state genuinely does not exist yet; the database of the class has already been migrated by
     * {@link #setUpSchema()}, so {@code IF NOT EXISTS} would short-circuit the very step under test.
     */
    protected void createFreshDatabase(String database) throws SQLException {
        try (Connection admin = openAdmin(server); Statement statement = admin.createStatement()) {
            String quoted = statement.enquoteIdentifier(database, true);
            statement.execute("DROP DATABASE IF EXISTS " + quoted + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + quoted);
        }
        if (!extraDatabases.contains(database)) {
            extraDatabases.add(database);
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
                     server.urlForDatabase(classDatabase), credentials(server));
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

    private static Connection openAdmin(PostgresTestServer target) throws SQLException {
        return DriverManager.getConnection(target.adminUrl(), credentials(target));
    }

    private static Properties credentials(PostgresTestServer target) {
        Properties properties = new Properties();
        properties.setProperty("user", target.user());
        properties.setProperty("password", target.password());
        properties.setProperty("connectTimeout", Integer.toString(CONNECT_TIMEOUT_SECONDS));
        return properties;
    }

    /** The cause named by its SQL state class, with the URL stripped of its query and never the password. */
    private static String describe(SQLException failure, UnaryOperator<String> properties,
                                   UnaryOperator<String> environment) {
        String target;
        try {
            target = PostgresTestServer.resolve(properties, environment).toString();
        } catch (PostgresTestServer.MissingParameterException impossible) {
            target = "the configured server";
        }
        String state = failure.getSQLState() == null ? "" : failure.getSQLState();
        String cause;
        if (state.startsWith("08")) {
            cause = "the PostgreSQL test server is unreachable";
        } else if (state.startsWith("28")) {
            cause = "the PostgreSQL test server refused the authentication";
        } else if ("42501".equals(state)) {
            cause = "the PostgreSQL test user lacks the CREATEDB privilege";
        } else {
            cause = "the PostgreSQL test server could not create the database of the class";
        }
        return String.format(Locale.ROOT, "%s (%s, SQL state %s)", cause, target, state.isEmpty() ? "none" : state);
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
