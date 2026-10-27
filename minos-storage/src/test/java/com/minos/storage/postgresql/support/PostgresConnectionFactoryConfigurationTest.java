package com.minos.storage.postgresql.support;

import com.minos.storage.StorageBackendConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Configuration and fail-closed behaviour of the pool that need no PostgreSQL server: refusals happen before
 * any credential is handed to the driver, and a pool that cannot connect never leaks a lease.
 */
class PostgresConnectionFactoryConfigurationTest {
    private static final String LOOPBACK_URL = "jdbc:postgresql://127.0.0.1:5432/minos_test";
    private static final String FIXTURE_PASSWORD = "fixture-value";

    @TempDir Path home;

    @Test
    void aLocalBackendConfigurationIsRefused() {
        StorageBackendConfiguration local = new StorageBackendConfiguration(
                "local", home, LOOPBACK_URL, "minos", FIXTURE_PASSWORD, "minos");

        IOException refusal = assertThrows(IOException.class, () -> new PostgresConnectionFactory(local));

        assertEquals("PostgreSQL configuration required", refusal.getMessage());
    }

    @Test
    void aMissingConfigurationIsRefused() {
        assertThrows(NullPointerException.class, () -> new PostgresConnectionFactory(null));
    }

    @Test
    void aMissingSettingIsNamedByItsEnvironmentVariableAndNeverEchoesTheOthers() {
        IOException url = assertThrows(IOException.class,
                () -> new PostgresConnectionFactory(configuration(null, "minos", FIXTURE_PASSWORD)));
        IOException user = assertThrows(IOException.class,
                () -> new PostgresConnectionFactory(configuration(LOOPBACK_URL, " ", FIXTURE_PASSWORD)));
        IOException password = assertThrows(IOException.class,
                () -> new PostgresConnectionFactory(configuration(LOOPBACK_URL, "minos", null)));

        assertEquals("missing required PostgreSQL setting: MINOS_POSTGRES_URL", url.getMessage());
        assertEquals("missing required PostgreSQL setting: MINOS_POSTGRES_USER", user.getMessage());
        assertEquals("missing required PostgreSQL setting: MINOS_POSTGRES_PASSWORD", password.getMessage());
        assertFalse(user.getMessage().contains(FIXTURE_PASSWORD));
    }

    @Test
    void thePoolSizeMustStayBetweenOneAndOneHundredTwentyEight() throws Exception {
        StorageBackendConfiguration configuration = configuration(LOOPBACK_URL, "minos", FIXTURE_PASSWORD);
        Duration timeout = Duration.ofSeconds(1);

        for (int size : new int[]{0, -1, 129}) {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> new PostgresConnectionFactory(configuration, size, timeout, 1, 1), "size " + size);
            assertEquals("maxPoolSize must be between 1 and 128", refusal.getMessage());
        }
        for (int size : new int[]{1, 128}) {
            try (PostgresConnectionFactory accepted = new PostgresConnectionFactory(configuration, size, timeout, 1, 1)) {
                assertEquals(size, accepted.poolStats().maximumSize());
            }
        }
    }

    @Test
    void theAcquireTimeoutMustBePositive() {
        StorageBackendConfiguration configuration = configuration(LOOPBACK_URL, "minos", FIXTURE_PASSWORD);

        for (Duration timeout : new Duration[]{Duration.ZERO, Duration.ofMillis(-1)}) {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> new PostgresConnectionFactory(configuration, 1, timeout, 1, 1), timeout.toString());
            assertEquals("acquireTimeout must be positive", refusal.getMessage());
        }
        assertThrows(NullPointerException.class, () -> new PostgresConnectionFactory(configuration, 1, null, 1, 1));
    }

    @Test
    void theJdbcTimeoutsMustBePositive() {
        StorageBackendConfiguration configuration = configuration(LOOPBACK_URL, "minos", FIXTURE_PASSWORD);
        Duration timeout = Duration.ofSeconds(1);

        IllegalArgumentException connect = assertThrows(IllegalArgumentException.class,
                () -> new PostgresConnectionFactory(configuration, 1, timeout, 0, 1));
        IllegalArgumentException socket = assertThrows(IllegalArgumentException.class,
                () -> new PostgresConnectionFactory(configuration, 1, timeout, 1, 0));

        assertEquals("JDBC timeouts must be positive", connect.getMessage());
        assertEquals("JDBC timeouts must be positive", socket.getMessage());
    }

    @Test
    void aFreshPoolReportsItsDefaultsAndOpensNoConnection() throws Exception {
        try (PostgresConnectionFactory factory = new PostgresConnectionFactory(
                configuration("  " + LOOPBACK_URL + "  ", "minos", FIXTURE_PASSWORD))) {
            PostgresConnectionFactory.PoolStats stats = factory.poolStats();

            assertEquals(new PostgresConnectionFactory.PoolStats(
                    PostgresConnectionFactory.DEFAULT_MAX_POOL_SIZE, 0, 0, 0, 0L, 0, 0L), stats);
            assertEquals(PostgresConnectionFactory.DEFAULT_ACQUIRE_TIMEOUT, factory.acquireTimeout());
            assertEquals("minos", factory.schema());
        }
    }

    @Test
    void aClosedPoolRefusesEveryKindOfLease() throws Exception {
        PostgresConnectionFactory factory = new PostgresConnectionFactory(
                configuration(LOOPBACK_URL, "minos", FIXTURE_PASSWORD));
        factory.close();
        AtomicBoolean executed = new AtomicBoolean();

        SQLException query = assertThrows(SQLException.class, () -> factory.withConnection(connection -> {
            executed.set(true);
            return null;
        }));
        SQLException transaction = assertThrows(SQLException.class, () -> factory.inTransaction(connection -> {
            executed.set(true);
            return null;
        }));
        SQLException scoped = assertThrows(SQLException.class, factory::openScopedConnection);
        SQLException dedicated = assertThrows(SQLException.class, factory::openDedicatedConnection);

        for (SQLException refusal : new SQLException[]{query, transaction, scoped, dedicated}) {
            assertEquals("PostgreSQL connection pool is closed", refusal.getMessage());
        }
        assertFalse(executed.get(), "no work may run on a closed pool");
        assertEquals(0, factory.poolStats().leased());
        assertEquals(0, factory.poolStats().dedicated());
    }

    @Test
    void missingWorkAndMissingLeaseAreRefusedBeforeAnyConnectionIsBorrowed() throws Exception {
        try (PostgresConnectionFactory factory = new PostgresConnectionFactory(
                configuration(LOOPBACK_URL, "minos", FIXTURE_PASSWORD))) {
            assertThrows(NullPointerException.class, () -> factory.withConnection(null));
            assertThrows(NullPointerException.class, () -> factory.inTransaction(null));
            assertThrows(NullPointerException.class, () -> factory.bindDedicatedConnection(null));
            assertEquals(0, factory.poolStats().physical());
        }
    }

    @Test
    void anUnreachableServerFailsWithoutLeakingALeaseOrThePassword() throws Exception {
        int closedPort;
        try (ServerSocket reserved = new ServerSocket(0)) {
            closedPort = reserved.getLocalPort();
        }
        StorageBackendConfiguration configuration = configuration(
                "jdbc:postgresql://127.0.0.1:" + closedPort + "/minos_test", "minos", FIXTURE_PASSWORD);

        try (PostgresConnectionFactory factory = new PostgresConnectionFactory(
                configuration, 1, Duration.ofSeconds(5), 2, 2)) {
            // The pool holds a single lease: a leaked one would turn the second attempt into "pool exhausted".
            for (int attempt = 0; attempt < 2; attempt++) {
                SQLException query = assertThrows(SQLException.class, () -> factory.withConnection(connection -> null));
                assertTrue(PostgresConnectionFactory.isConnectionFailure(query), query.getSQLState());
                assertFalse(String.valueOf(query.getMessage()).contains(FIXTURE_PASSWORD));

                SQLException dedicated = assertThrows(SQLException.class, factory::openDedicatedConnection);
                assertTrue(PostgresConnectionFactory.isConnectionFailure(dedicated), dedicated.getSQLState());
            }
            assertEquals(new PostgresConnectionFactory.PoolStats(1, 0, 0, 0, 0L, 0, 0L), factory.poolStats());
        }
    }

    @Test
    void anInterruptedThreadIsRefusedALeaseAndKeepsItsInterruptFlag() throws Exception {
        try (PostgresConnectionFactory factory = new PostgresConnectionFactory(
                configuration(LOOPBACK_URL, "minos", FIXTURE_PASSWORD))) {
            AtomicReference<Throwable> query = new AtomicReference<>();
            AtomicReference<Throwable> dedicated = new AtomicReference<>();
            AtomicBoolean stillInterrupted = new AtomicBoolean();
            Thread interrupted = Thread.ofPlatform().start(() -> {
                Thread.currentThread().interrupt();
                try {
                    factory.withConnection(connection -> null);
                } catch (Exception thrown) {
                    query.set(thrown);
                }
                boolean afterQuery = Thread.currentThread().isInterrupted();
                try {
                    factory.openDedicatedConnection();
                } catch (Exception thrown) {
                    dedicated.set(thrown);
                }
                stillInterrupted.set(afterQuery && Thread.currentThread().isInterrupted());
            });
            interrupted.join();

            assertEquals("interrupted while acquiring PostgreSQL connection",
                    assertInstanceOf(SQLException.class, query.get()).getMessage());
            assertEquals("interrupted while acquiring dedicated PostgreSQL connection",
                    assertInstanceOf(SQLException.class, dedicated.get()).getMessage());
            assertInstanceOf(InterruptedException.class, query.get().getCause());
            assertTrue(stillInterrupted.get(), "the interrupt flag must be restored for the caller");
            assertEquals(new PostgresConnectionFactory.PoolStats(
                    PostgresConnectionFactory.DEFAULT_MAX_POOL_SIZE, 0, 0, 0, 0L, 0, 0L), factory.poolStats());
        }
    }

    @Test
    void aConnectionFailureIsRecognizedAnywhereInTheSqlExceptionChain() {
        SQLException chained = new SQLException("statement failed", "42601");
        chained.setNextException(new SQLException("no state"));
        chained.getNextException().setNextException(new SQLException("connection lost", "08003"));
        SQLException unrelated = new SQLException("no state");
        unrelated.setNextException(new SQLException("serialization", "40001"));

        assertTrue(PostgresConnectionFactory.isConnectionFailure(chained));
        assertFalse(PostgresConnectionFactory.isConnectionFailure(unrelated));
        assertFalse(PostgresConnectionFactory.isConnectionFailure(new SQLException("looks like 08", "80800")));
    }

    private StorageBackendConfiguration configuration(String url, String user, String password) {
        return new StorageBackendConfiguration("postgresql", home, url, user, password, "minos");
    }
}
