package com.minos.storage.postgresql.support;

import com.minos.io.CommitUncertainException;
import com.minos.storage.postgresql.PostgresTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lease accounting of the bounded pool against a real PostgreSQL server: what is committed, what is rolled back,
 * which connection is reused and which one is discarded, and who may release a lease.
 */
class PostgresConnectionFactoryPoolTest extends PostgresTestSupport {
    private static final Duration SHORT_ACQUIRE_TIMEOUT = Duration.ofMillis(200);

    @TempDir Path home;

    /** The base class opens one pool per test and never closes it: release its idle session with the test. */
    @AfterEach
    void closeSharedPool() {
        if (connections != null) connections.close();
    }

    @Test
    void nestedWorkReusesTheLeasedConnectionAndReturnsItToThePool() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            AtomicReference<Connection> innerConnection = new AtomicReference<>();

            Connection outerConnection = pool.withConnection(outer -> {
                assertEquals(1, pool.poolStats().leased());
                pool.withConnection(inner -> {
                    innerConnection.set(inner);
                    return null;
                });
                assertEquals(1, pool.poolStats().leased(), "nested work must not borrow a second lease");
                return outer;
            });

            assertSame(outerConnection, innerConnection.get());
            assertEquals(new PostgresConnectionFactory.PoolStats(2, 0, 1, 1, 0L, 0, 0L), pool.poolStats());
            assertSame(outerConnection, pool.withConnection(connection -> connection),
                    "an idle healthy connection must be reused");
            assertEquals(1, pool.poolStats().physical());
        }
    }

    @Test
    void aTransactionCommitsItsWorkAndRollsBackOnEveryKindOfFailure() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            pool.inTransaction(connection -> insertWorkspace(connection, "committed"));
            PostgresConnectionFactory.ConnectionWork<Void> ioFailure = connection -> {
                insertWorkspace(connection, "io-failure");
                throw new IOException("work failed");
            };
            PostgresConnectionFactory.ConnectionWork<Void> runtimeFailure = connection -> {
                insertWorkspace(connection, "runtime-failure");
                throw new IllegalStateException("work failed");
            };
            PostgresConnectionFactory.ConnectionWork<Void> sqlFailure = connection -> {
                insertWorkspace(connection, "sql-failure");
                try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM table_that_does_not_exist")) {
                    statement.executeQuery();
                }
                return null;
            };

            assertThrows(IOException.class, () -> pool.inTransaction(ioFailure));
            assertThrows(IllegalStateException.class, () -> pool.inTransaction(runtimeFailure));
            SQLException sql = assertThrows(SQLException.class, () -> pool.inTransaction(sqlFailure));

            assertFalse(PostgresConnectionFactory.isConnectionFailure(sql));
            assertEquals(1, countWorkspaces(pool, "committed"));
            assertEquals(0, countWorkspaces(pool, "io-failure"));
            assertEquals(0, countWorkspaces(pool, "runtime-failure"));
            assertEquals(0, countWorkspaces(pool, "sql-failure"));
            assertEquals(new PostgresConnectionFactory.PoolStats(2, 0, 1, 1, 0L, 0, 0L), pool.poolStats(),
                    "a failure that is not a connection loss keeps the connection in the pool");
            assertTrue(autoCommit(pool), "autocommit must be restored after a transaction");
        }
    }

    @Test
    void aNestedTransactionJoinsTheOuterOne() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            pool.inTransaction(outer -> {
                insertWorkspace(outer, "outer");
                Connection joined = pool.inTransaction(inner -> {
                    insertWorkspace(inner, "inner");
                    return inner;
                });
                assertSame(outer, joined);
                assertFalse(outer.getAutoCommit(), "the nested transaction must not commit the outer one");
                return null;
            });

            assertEquals(1, countWorkspaces(pool, "outer"));
            assertEquals(1, countWorkspaces(pool, "inner"));
        }
    }

    @Test
    void aFailedNestedTransactionMarksTheOuterOneRollbackOnlyEvenWhenTheFailureIsSwallowed() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            PostgresConnectionFactory.ConnectionWork<Void> failingInner = inner -> {
                insertWorkspace(inner, "inner");
                throw new IOException("nested work failed");
            };
            PostgresConnectionFactory.ConnectionWork<Void> swallowingOuter = outer -> {
                insertWorkspace(outer, "outer");
                try {
                    pool.inTransaction(failingInner);
                } catch (IOException _) {
                    // The outer work deliberately ignores the nested failure: the commit must still be refused.
                }
                return null;
            };

            SQLException refusal = assertThrows(SQLException.class, () -> pool.inTransaction(swallowingOuter));

            assertEquals("PostgreSQL transaction was marked rollback-only by nested work", refusal.getMessage());
            assertEquals(0, countWorkspaces(pool, "outer"));
            assertEquals(0, countWorkspaces(pool, "inner"));
            pool.inTransaction(connection -> insertWorkspace(connection, "after"));
            assertEquals(1, countWorkspaces(pool, "after"), "the rollback-only mark must not outlive its transaction");
        }
    }

    @Test
    void aTransactionOnALeaseWithAutoCommitOffKeepsItOffAndTheReleaseRollsBackWhatWasNotCommitted() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            pool.withConnection(connection -> {
                connection.setAutoCommit(false);
                pool.inTransaction(transaction -> insertWorkspace(transaction, "committed"));
                assertFalse(connection.getAutoCommit(), "the transaction must restore the mode it found");
                insertWorkspace(connection, "left-uncommitted");
                return null;
            });

            assertEquals(1, countWorkspaces(pool, "committed"));
            assertEquals(0, countWorkspaces(pool, "left-uncommitted"));
            assertTrue(autoCommit(pool), "a pooled connection is handed out in autocommit");
            assertEquals(1, pool.poolStats().physical());
        }
    }

    @Test
    void aConnectionFailureDiscardsTheConnectionInEveryExecutionPath() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            PostgresConnectionFactory.ConnectionWork<Void> ordinary = connection -> {
                throw new SQLException("syntax", "42601");
            };
            PostgresConnectionFactory.ConnectionWork<Void> lost = connection -> {
                throw new SQLException("connection lost", "08006");
            };
            PostgresConnectionFactory.ConnectionWork<Void> lostInNestedTransaction = connection -> pool.inTransaction(lost);

            assertThrows(SQLException.class, () -> pool.withConnection(ordinary));
            assertEquals(1, pool.poolStats().physical(), "an ordinary SQL failure keeps the connection");

            assertThrows(SQLException.class, () -> pool.withConnection(lost));
            assertEquals(0, pool.poolStats().physical());
            assertEquals(0, pool.poolStats().idle());

            assertThrows(SQLException.class, () -> pool.inTransaction(lost));
            assertEquals(0, pool.poolStats().physical());

            assertThrows(SQLException.class, () -> pool.inTransaction(lostInNestedTransaction));
            assertEquals(new PostgresConnectionFactory.PoolStats(2, 0, 0, 0, 0L, 0, 0L), pool.poolStats());
        }
    }

    @Test
    void aConnectionThatDiedWhileIdleIsReplacedInsteadOfBeingHandedOut() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            Connection first = pool.withConnection(connection -> connection);
            first.close();

            Connection second = pool.withConnection(connection -> {
                assertTrue(connection.isValid(5));
                return connection;
            });

            assertNotSame(first, second);
            assertEquals(new PostgresConnectionFactory.PoolStats(2, 0, 1, 1, 0L, 0, 0L), pool.poolStats());
        }
    }

    @Test
    void aConnectionClosedByItsOwnWorkIsNotReturnedToThePool() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            pool.withConnection(connection -> {
                connection.close();
                return null;
            });

            assertEquals(new PostgresConnectionFactory.PoolStats(2, 0, 0, 0, 0L, 0, 0L), pool.poolStats());
            assertEquals(1, selectOne(pool));
        }
    }

    @Test
    void aCommitOnAConnectionLostBeforeItsAcknowledgementIsReportedAsUncertain() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            PostgresConnectionFactory.ConnectionWork<Void> losesItsConnection = connection -> {
                insertWorkspace(connection, "uncertain");
                connection.close();
                return null;
            };

            CommitUncertainException uncertain = assertThrows(CommitUncertainException.class,
                    () -> pool.inTransaction(losesItsConnection));

            assertEquals("PostgreSQL transaction commit acknowledgement was lost", uncertain.getMessage());
            assertInstanceOf(SQLException.class, uncertain.getCause());
            assertEquals(1, uncertain.getSuppressed().length,
                    "the failure to restore autocommit must be attached, not thrown instead");
            assertEquals(new PostgresConnectionFactory.PoolStats(2, 0, 0, 0, 0L, 0, 0L), pool.poolStats());
            assertEquals(0, countWorkspaces(pool, "uncertain"));
        }
    }

    @Test
    void aTransactionWhoseWorkReEnablesAutoCommitIsRefusedAtCommitAndThePoolStaysServiceable() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            PostgresConnectionFactory.ConnectionWork<Void> reEnablesAutoCommit = connection -> {
                connection.setAutoCommit(true);
                return null;
            };

            SQLException refusal = assertThrows(SQLException.class, () -> pool.inTransaction(reEnablesAutoCommit));

            assertFalse(PostgresConnectionFactory.isConnectionFailure(refusal));
            assertEquals(1, selectOne(pool));
            assertEquals(0, pool.poolStats().leased());
        }
    }

    @Test
    void aScopedLeaseHoldsThePoolConnectionUntilItsOwnerClosesIt() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            PostgresConnectionFactory.ScopedConnectionLease lease = pool.openScopedConnection();
            Connection connection = lease.connection();

            assertEquals(1, pool.poolStats().leased());
            SQLException nested = assertThrows(SQLException.class, pool::openScopedConnection);
            assertEquals("cannot open a scoped PostgreSQL connection inside another connection lease", nested.getMessage());
            assertSame(connection, pool.withConnection(reused -> reused), "work on the owner thread reuses the scoped lease");
            assertEquals(1, pool.poolStats().leased(), "nested work must not release the scoped lease");

            lease.close();
            lease.close();

            assertEquals(new PostgresConnectionFactory.PoolStats(2, 0, 1, 1, 0L, 0, 0L), pool.poolStats());
            IllegalStateException closed = assertThrows(IllegalStateException.class, lease::connection);
            assertEquals("PostgreSQL scoped connection lease is closed", closed.getMessage());
            assertFalse(connection.isClosed(), "a reusable connection goes back to the pool open");
        }
    }

    @Test
    void anInvalidatedScopedLeaseDiscardsItsConnection() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            PostgresConnectionFactory.ScopedConnectionLease lease = pool.openScopedConnection();
            Connection connection = lease.connection();

            lease.invalidate();
            lease.close();

            assertTrue(connection.isClosed());
            assertEquals(new PostgresConnectionFactory.PoolStats(2, 0, 0, 0, 0L, 0, 0L), pool.poolStats());
        }
    }

    @Test
    void aScopedLeaseCanOnlyBeUsedByItsOwnerThread() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            PostgresConnectionFactory.ScopedConnectionLease lease = pool.openScopedConnection();

            Throwable connection = onAnotherThread(lease::connection);
            Throwable invalidate = onAnotherThread(lease::invalidate);
            Throwable close = onAnotherThread(lease::close);

            for (Throwable refusal : new Throwable[]{connection, invalidate, close}) {
                assertEquals("PostgreSQL scoped connection lease must be used by its owner thread",
                        assertInstanceOf(IllegalStateException.class, refusal).getMessage());
            }
            assertEquals(1, pool.poolStats().leased(), "a foreign thread must not release the lease");
            lease.close();
            assertEquals(new PostgresConnectionFactory.PoolStats(2, 0, 1, 1, 0L, 0, 0L), pool.poolStats(),
                    "a refused foreign invalidation must not discard the connection");
        }
    }

    @Test
    void closingThePoolClosesIdleConnectionsAndALeaseReleasedAfterwardsIsClosedInsteadOfPooled() throws Exception {
        PostgresConnectionFactory pool = pool(2);
        PostgresConnectionFactory.ScopedConnectionLease lease = pool.openScopedConnection();
        Connection leased = lease.connection();
        AtomicReference<Connection> idle = new AtomicReference<>();
        assertNull(onAnotherThread(() -> idle.set(pool.withConnection(connection -> connection))));
        assertEquals(new PostgresConnectionFactory.PoolStats(2, 1, 1, 2, 0L, 0, 0L), pool.poolStats());

        pool.close();

        assertTrue(idle.get().isClosed());
        assertFalse(leased.isClosed(), "closing the pool must not pull a connection from under running work");
        lease.close();
        assertTrue(leased.isClosed());
        assertEquals(new PostgresConnectionFactory.PoolStats(2, 0, 0, 0, 0L, 0, 0L), pool.poolStats());
    }

    @Test
    void anExhaustedPoolRefusesANewLeaseAfterTheAcquireTimeoutAndCountsIt() throws Exception {
        try (PostgresConnectionFactory pool = pool(1)) {
            PostgresConnectionFactory.ScopedConnectionLease lease = pool.openScopedConnection();
            AtomicBoolean executed = new AtomicBoolean();

            Throwable refusal = onAnotherThread(() -> pool.withConnection(connection -> {
                executed.set(true);
                return null;
            }));

            assertEquals("PostgreSQL connection pool exhausted after " + SHORT_ACQUIRE_TIMEOUT,
                    assertInstanceOf(SQLException.class, refusal).getMessage());
            assertFalse(executed.get(), "no work may run without a lease");
            assertEquals(new PostgresConnectionFactory.PoolStats(1, 1, 0, 1, 1L, 0, 0L), pool.poolStats());

            lease.close();
            assertNull(onAnotherThread(() -> selectOne(pool)),
                    "the released lease must be available to another thread");
        }
    }

    @Test
    void aDedicatedLeaseLivesOutsideTheQueryPoolAndIsClosedWithItsLease() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            PostgresConnectionFactory.DedicatedConnectionLease lease = pool.openDedicatedConnection();
            Connection connection = lease.connection();

            assertEquals(1, selectOne(connection));
            assertEquals(new PostgresConnectionFactory.PoolStats(2, 0, 0, 0, 0L, 1, 0L), pool.poolStats());
            assertNotSame(connection, pool.withConnection(pooled -> pooled),
                    "an unbound dedicated connection is never handed to ordinary work");

            lease.close();
            lease.close();

            assertTrue(connection.isClosed());
            assertEquals(0, pool.poolStats().dedicated(), "a second close must not release the budget twice");
            IllegalStateException closed = assertThrows(IllegalStateException.class, lease::connection);
            assertEquals("PostgreSQL dedicated connection lease is closed", closed.getMessage());
        }
    }

    @Test
    void aDedicatedLeaseReleasesItsBudgetEvenWhenInvalidatedOrAlreadyClosedByItsUser() throws Exception {
        try (PostgresConnectionFactory pool = pool(1)) {
            PostgresConnectionFactory.DedicatedConnectionLease invalidated = pool.openDedicatedConnection();
            Connection first = invalidated.connection();
            invalidated.invalidate();
            invalidated.close();

            PostgresConnectionFactory.DedicatedConnectionLease closedByUser = pool.openDedicatedConnection();
            Connection second = closedByUser.connection();
            second.close();
            closedByUser.close();

            assertTrue(first.isClosed());
            assertTrue(second.isClosed());
            assertEquals(new PostgresConnectionFactory.PoolStats(1, 0, 0, 0, 0L, 0, 0L), pool.poolStats());
            try (PostgresConnectionFactory.DedicatedConnectionLease next = pool.openDedicatedConnection()) {
                assertEquals(1, selectOne(next.connection()), "the single dedicated slot must be free again");
            }
        }
    }

    @Test
    void aDedicatedLeaseCanOnlyBeUsedByItsOwnerThread() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            PostgresConnectionFactory.DedicatedConnectionLease lease = pool.openDedicatedConnection();
            Connection connection = lease.connection();

            Throwable use = onAnotherThread(lease::connection);
            Throwable invalidate = onAnotherThread(lease::invalidate);
            Throwable close = onAnotherThread(lease::close);

            for (Throwable refusal : new Throwable[]{use, invalidate, close}) {
                assertEquals("PostgreSQL dedicated connection lease must be used by its owner thread",
                        assertInstanceOf(IllegalStateException.class, refusal).getMessage());
            }
            assertFalse(connection.isClosed(), "a foreign thread must not close the dedicated session");
            assertEquals(1, pool.poolStats().dedicated());
            lease.close();
            assertEquals(0, pool.poolStats().dedicated());
        }
    }

    @Test
    void theDedicatedBudgetIsBoundedAndSeparateFromTheQueryPool() throws Exception {
        try (PostgresConnectionFactory pool = pool(1)) {
            PostgresConnectionFactory.DedicatedConnectionLease first = pool.openDedicatedConnection();

            SQLException refusal = assertThrows(SQLException.class, pool::openDedicatedConnection);

            assertEquals("PostgreSQL dedicated connection budget exhausted after " + SHORT_ACQUIRE_TIMEOUT,
                    refusal.getMessage());
            assertEquals(new PostgresConnectionFactory.PoolStats(1, 0, 0, 0, 0L, 1, 1L), pool.poolStats());
            assertEquals(1, selectOne(pool),
                    "an exhausted dedicated budget must not starve ordinary queries");

            first.close();
            try (PostgresConnectionFactory.DedicatedConnectionLease second = pool.openDedicatedConnection()) {
                assertEquals(1, selectOne(second.connection()));
            }
        }
    }

    @Test
    void aBoundDedicatedConnectionServesEveryNestedOperationOfItsThread() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            PostgresConnectionFactory.DedicatedConnectionLease lease = pool.openDedicatedConnection();
            Connection dedicated = lease.connection();
            PostgresConnectionFactory.DedicatedConnectionContext context = pool.bindDedicatedConnection(lease);

            assertSame(dedicated, pool.withConnection(connection -> connection));
            assertSame(dedicated, pool.inTransaction(connection -> {
                insertWorkspace(connection, "bound");
                return connection;
            }));
            assertEquals(0, pool.poolStats().leased(), "bound work must not consume query capacity");
            IllegalStateException rebind = assertThrows(IllegalStateException.class,
                    () -> pool.bindDedicatedConnection(lease));
            assertEquals("cannot bind a PostgreSQL dedicated connection inside another lease", rebind.getMessage());

            context.close();
            context.close();

            assertNotSame(dedicated, pool.withConnection(connection -> connection),
                    "an unbound thread goes back to the query pool");
            assertFalse(dedicated.isClosed(), "unbinding must not close the session that still owns its lease");
            assertEquals(1, countWorkspaces(pool, "bound"));
            lease.close();
            assertTrue(dedicated.isClosed());
        }
    }

    @Test
    void aBoundDedicatedConnectionCannotBeUnboundDuringATransactionNorByAnotherThread() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            PostgresConnectionFactory.DedicatedConnectionLease lease = pool.openDedicatedConnection();
            Connection dedicated = lease.connection();
            PostgresConnectionFactory.DedicatedConnectionContext context = pool.bindDedicatedConnection(lease);
            PostgresConnectionFactory.ConnectionWork<Void> unbindsInsideTransaction = connection -> {
                insertWorkspace(connection, "unbound-in-transaction");
                context.close();
                return null;
            };

            IllegalStateException inTransaction = assertThrows(IllegalStateException.class,
                    () -> pool.inTransaction(unbindsInsideTransaction));
            Throwable foreign = onAnotherThread(context::close);

            assertEquals("cannot unbind PostgreSQL dedicated connection during a transaction", inTransaction.getMessage());
            assertEquals("PostgreSQL dedicated connection context must be released by its owner thread",
                    assertInstanceOf(IllegalStateException.class, foreign).getMessage());
            assertSame(dedicated, pool.withConnection(connection -> connection), "the binding must survive both refusals");

            context.close();
            lease.close();

            assertTrue(dedicated.isClosed());
            assertEquals(0, countWorkspaces(pool, "unbound-in-transaction"));
            assertEquals(0, pool.poolStats().dedicated());
        }
    }

    @Test
    void aConnectionLossOnABoundDedicatedConnectionInvalidatesItsLease() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            PostgresConnectionFactory.DedicatedConnectionLease lease = pool.openDedicatedConnection();
            Connection dedicated = lease.connection();
            PostgresConnectionFactory.DedicatedConnectionContext context = pool.bindDedicatedConnection(lease);
            PostgresConnectionFactory.ConnectionWork<Void> lost = connection -> {
                throw new SQLException("connection lost", "08006");
            };

            assertThrows(SQLException.class, () -> pool.withConnection(lost));
            context.close();
            lease.close();

            assertTrue(dedicated.isClosed());
            assertEquals(new PostgresConnectionFactory.PoolStats(2, 0, 0, 0, 0L, 0, 0L), pool.poolStats());
        }
    }

    private PostgresConnectionFactory pool(int maximumSize) throws Exception {
        return PostgresTestPools.sized(connections, home, maximumSize, SHORT_ACQUIRE_TIMEOUT);
    }

    private static Void insertWorkspace(Connection connection, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO workspaces(id,name,created_at,updated_at) VALUES (?,?,now(),now())")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, name);
            statement.executeUpdate();
        }
        return null;
    }

    private static int countWorkspaces(PostgresConnectionFactory pool, String name) throws Exception {
        return pool.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT count(*) FROM workspaces WHERE name=?")) {
                statement.setString(1, name);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    return result.getInt(1);
                }
            }
        });
    }

    private static int selectOne(PostgresConnectionFactory pool) throws Exception {
        return pool.withConnection(PostgresConnectionFactoryPoolTest::selectOne);
    }

    private static boolean autoCommit(PostgresConnectionFactory pool) throws Exception {
        return pool.withConnection(Connection::getAutoCommit);
    }

    private static int selectOne(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1");
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }

    /** Runs {@code action} to completion on a fresh thread and returns what it threw, or {@code null}. */
    private static Throwable onAnotherThread(ThrowingAction action) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofPlatform().start(() -> {
            try {
                action.run();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        thread.join();
        return failure.get();
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
