package com.minos.storage.postgresql.support;

import com.minos.io.CommitUncertainException;
import com.minos.storage.StorageBackendConfiguration;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When the pool pays a validation round trip, which connection it refuses to put back to rest, and when a call
 * hit by a connection loss is replayed. Everything runs on spy connections and an injected monotonic clock:
 * no PostgreSQL server, no waiting.
 */
class PostgresConnectionFactoryValidationTest {
    private static final String LOOPBACK_URL = "jdbc:postgresql://127.0.0.1:5432/minos_test";
    private static final String FIXTURE_PASSWORD = "fixture-value";
    private static final long THRESHOLD_NANOS = PostgresConnectionFactory.IDLE_VALIDATION_THRESHOLD.toNanos();

    @TempDir Path home;

    private final SpyDatabase database = new SpyDatabase();
    private final AtomicLong now = new AtomicLong();

    @Test
    void anImmediateBorrowAndReturnPaysNoValidationRoundTrip() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            Connection first = pool.withConnection(connection -> connection);
            Connection second = pool.withConnection(connection -> connection);
            pool.withConnection(query("SELECT 1"));

            assertSame(first, second, "the idle connection is reused");
            assertEquals(1, database.opened());
            assertEquals(0, database.validations());
            assertEquals(new PostgresConnectionFactory.PoolStats(2, 0, 1, 1, 0L, 0, 0L), pool.poolStats());
        }
    }

    @Test
    void aConnectionIdleUpToTheThresholdIsValidatedExactlyOnceWhenBorrowed() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            pool.withConnection(query("SELECT 1"));

            now.addAndGet(THRESHOLD_NANOS - 1);
            pool.withConnection(query("SELECT 2"));
            assertEquals(0, database.validations(), "one nanosecond under the threshold is still fresh");

            now.addAndGet(THRESHOLD_NANOS);
            pool.withConnection(query("SELECT 3"));

            assertEquals(1, database.session(1).validations.get());
            assertEquals(List.of("1:SELECT 1", "1:SELECT 2", "1:SELECT 3"), database.executed());
            assertEquals(1, database.opened());
        }
    }

    @Test
    void theIdleDurationSurvivesAWrapOfTheMonotonicClock() throws Exception {
        now.set(Long.MAX_VALUE - 5);
        try (PostgresConnectionFactory pool = pool(1)) {
            pool.withConnection(query("SELECT 1"));
            pool.withConnection(query("SELECT 2"));
            assertEquals(0, database.validations());

            now.addAndGet(THRESHOLD_NANOS);
            pool.withConnection(query("SELECT 3"));

            assertTrue(now.get() < 0, "the clock wrapped");
            assertEquals(1, database.validations());
        }
    }

    @Test
    void aConnectionFoundInvalidAfterTheThresholdIsClosedAndReplaced() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            pool.withConnection(query("SELECT 1"));
            database.session(1).broken = true;
            now.addAndGet(THRESHOLD_NANOS);
            AtomicInteger attempts = new AtomicInteger();

            pool.withConnection(counting(attempts, query("SELECT 2")));

            assertEquals(1, attempts.get(), "the dead connection never reached the caller");
            assertEquals(1, database.session(1).validations.get());
            assertTrue(database.session(1).closed);
            assertEquals(List.of("1:SELECT 1", "2:SELECT 2"), database.executed());
            assertEquals(new PostgresConnectionFactory.PoolStats(2, 0, 1, 1, 0L, 0, 0L), pool.poolStats());
        }
    }

    @Test
    void aValidationThatThrowsDiscardsTheConnection() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            pool.withConnection(query("SELECT 1"));
            database.session(1).validationFailure = new IllegalStateException("driver failure");
            now.addAndGet(THRESHOLD_NANOS);

            pool.withConnection(query("SELECT 2"));

            assertTrue(database.session(1).closed);
            assertEquals(List.of("1:SELECT 1", "2:SELECT 2"), database.executed());
        }
    }

    @Test
    void aConnectionClosedWhileIdleIsReplacedWithoutAnyRoundTrip() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            pool.withConnection(query("SELECT 1"));
            database.session(1).closed = true;

            pool.withConnection(query("SELECT 2"));

            assertEquals(0, database.validations());
            assertEquals(List.of("1:SELECT 1", "2:SELECT 2"), database.executed());
            assertEquals(new PostgresConnectionFactory.PoolStats(2, 0, 1, 1, 0L, 0, 0L), pool.poolStats());
        }
    }

    @Test
    void aConnectionReturnedAfterAnyFailureOfItsCallIsClosedAndNeverReused() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            PostgresConnectionFactory.ConnectionWork<Void> sqlFailure = connection -> {
                throw new SQLException("syntax", "42601");
            };
            PostgresConnectionFactory.ConnectionWork<Void> ioFailure = connection -> {
                throw new IOException("work failed");
            };
            PostgresConnectionFactory.ConnectionWork<Void> runtimeFailure = connection -> {
                throw new IllegalStateException("work failed");
            };

            assertThrows(SQLException.class, () -> pool.withConnection(sqlFailure));
            assertThrows(IOException.class, () -> pool.withConnection(ioFailure));
            assertThrows(IllegalStateException.class, () -> pool.inTransaction(runtimeFailure));

            assertEquals(3, database.opened(), "each failed call retired its connection");
            for (int ordinal = 1; ordinal <= 3; ordinal++) assertTrue(database.session(ordinal).closed);
            assertEquals(new PostgresConnectionFactory.PoolStats(2, 0, 0, 0, 0L, 0, 0L), pool.poolStats());
            pool.withConnection(query("SELECT 1"));
            assertEquals(List.of("4:SELECT 1"), database.executed());
            assertEquals(0, database.validations());
        }
    }

    @Test
    void aConnectionReturnedOutsideAutoCommitIsClosedWithoutARoundTrip() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            pool.withConnection(connection -> {
                connection.setAutoCommit(false);
                return null;
            });

            SpyDatabase.Session left = database.session(1);
            assertTrue(left.closed);
            assertEquals(0, left.rollbacks.get(), "closing the session is what discards its transaction");
            assertEquals(0, left.validations.get());
            assertEquals(new PostgresConnectionFactory.PoolStats(2, 0, 0, 0, 0L, 0, 0L), pool.poolStats());
        }
    }

    @Test
    void aConnectionWhoseLocalStateCannotBeReadIsClosedInsteadOfPooled() throws Exception {
        try (PostgresConnectionFactory pool = pool(4)) {
            pool.withConnection(connection -> {
                database.session(1).autoCommitFailure = new IllegalStateException("driver failure");
                return null;
            });
            pool.withConnection(connection -> {
                database.session(2).clearWarningsFailure = new SQLException("driver failure");
                return null;
            });
            pool.withConnection(connection -> {
                database.session(3).closeFailure = new SQLException("close failed");
                connection.setAutoCommit(false);
                return null;
            });
            pool.withConnection(connection -> {
                connection.close();
                return null;
            });

            for (int ordinal = 1; ordinal <= 4; ordinal++) assertTrue(database.session(ordinal).closed);
            assertEquals(new PostgresConnectionFactory.PoolStats(4, 0, 0, 0, 0L, 0, 0L), pool.poolStats());
        }
    }

    @Test
    void aReadOnAConnectionLostWhileFreshlyIdleIsReplayedOnceOnANewConnection() throws Exception {
        try (PostgresConnectionFactory pool = pool(1)) {
            pool.withConnection(query("SELECT 1"));
            database.session(1).broken = true;
            AtomicInteger attempts = new AtomicInteger();

            int result = pool.withConnection(counting(attempts, query("SELECT 2")));

            assertEquals(7, result);
            assertEquals(2, attempts.get(), "one failed attempt, one replay");
            assertEquals(0, database.validations(), "the replay is not paid for by a validation");
            assertTrue(database.session(1).closed);
            assertEquals(List.of("1:SELECT 1", "2:SELECT 2"), database.executed());
            assertEquals(new PostgresConnectionFactory.PoolStats(1, 0, 1, 1, 0L, 0, 0L), pool.poolStats(),
                    "the replay runs under the lease of the failed attempt, even in a pool of one");
        }
    }

    @Test
    void nestedWorkThatLosesTheConnectionIsReplayedWithTheWholeOuterCall() throws Exception {
        try (PostgresConnectionFactory pool = pool(1)) {
            pool.withConnection(query("SELECT 1"));
            database.session(1).broken = true;
            AtomicInteger outerAttempts = new AtomicInteger();
            AtomicInteger innerAttempts = new AtomicInteger();
            PostgresConnectionFactory.ConnectionWork<Integer> outer =
                    connection -> pool.withConnection(counting(innerAttempts, query("SELECT 2")));

            assertEquals(7, pool.withConnection(counting(outerAttempts, outer)));

            assertEquals(2, outerAttempts.get());
            assertEquals(2, innerAttempts.get());
            assertEquals(List.of("1:SELECT 1", "2:SELECT 2"), database.executed());
            assertEquals(new PostgresConnectionFactory.PoolStats(1, 0, 1, 1, 0L, 0, 0L), pool.poolStats());
        }
    }

    @Test
    void anOrdinarySqlFailureOnAnUnvalidatedConnectionIsNotReplayed() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            pool.withConnection(query("SELECT 1"));
            database.session(1).nextStatementFailure = new SQLException("syntax", "42601");
            AtomicInteger attempts = new AtomicInteger();

            SQLException failure = assertThrows(SQLException.class,
                    () -> pool.withConnection(counting(attempts, query("SELECT 2"))));

            assertFalse(PostgresConnectionFactory.isConnectionFailure(failure));
            assertEquals(1, attempts.get());
            assertEquals(1, database.opened());
            assertTrue(database.session(1).closed);
        }
    }

    @Test
    void aReplayThatFailsTooReportsItsFailureAndIsNotRetriedAgain() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            pool.withConnection(query("SELECT 1"));
            database.session(1).broken = true;
            AtomicInteger attempts = new AtomicInteger();
            PostgresConnectionFactory.ConnectionWork<Integer> alwaysLost = connection -> {
                database.session(database.opened()).broken = true;
                return query("SELECT 2").execute(connection);
            };

            SQLException failure = assertThrows(SQLException.class,
                    () -> pool.withConnection(counting(attempts, alwaysLost)));

            assertEquals(2, attempts.get());
            assertEquals(2, database.opened());
            assertTrue(PostgresConnectionFactory.isConnectionFailure(failure));
            assertEquals(1, failure.getSuppressed().length, "the first failure is kept for the operator");
            assertTrue(database.session(2).closed);
            assertEquals(List.of("1:SELECT 1"), database.executed());
            assertEquals(new PostgresConnectionFactory.PoolStats(2, 0, 0, 0, 0L, 0, 0L), pool.poolStats());
        }
    }

    @Test
    void aCallThatFailsTwiceWithTheSameExceptionReportsItUntouched() throws Exception {
        try (PostgresConnectionFactory pool = pool(1)) {
            pool.withConnection(query("SELECT 1"));
            SQLException lost = new SQLException("connection lost", SpyDatabase.CONNECTION_LOST);
            AtomicInteger attempts = new AtomicInteger();
            PostgresConnectionFactory.ConnectionWork<Void> failing = connection -> {
                throw lost;
            };

            SQLException failure = assertThrows(SQLException.class,
                    () -> pool.withConnection(counting(attempts, failing)));

            assertSame(lost, failure);
            assertEquals(0, failure.getSuppressed().length);
            assertEquals(2, attempts.get());
        }
    }

    @Test
    void aReplayThatCannotOpenAConnectionReportsTheSourceFailureAndReleasesItsLease() throws Exception {
        try (PostgresConnectionFactory pool = pool(1)) {
            pool.withConnection(query("SELECT 1"));
            database.session(1).broken = true;
            SQLException refused = new SQLException("refused", "08001");
            database.refuseConnections(refused);
            AtomicInteger attempts = new AtomicInteger();

            SQLException failure = assertThrows(SQLException.class,
                    () -> pool.withConnection(counting(attempts, query("SELECT 2"))));

            assertSame(refused, failure, "the pool adds no text of its own to a connection failure");
            assertEquals(1, failure.getSuppressed().length);
            assertEquals(1, attempts.get());
            assertEquals(new PostgresConnectionFactory.PoolStats(1, 0, 0, 0, 0L, 0, 0L), pool.poolStats());

            database.refuseConnections(null);
            assertEquals(7, pool.withConnection(query("SELECT 3")), "the single lease was released");
        }
    }

    @Test
    void aReplayIsRefusedOnceThePoolIsClosed() throws Exception {
        PostgresConnectionFactory pool = pool(1);
        pool.withConnection(query("SELECT 1"));
        AtomicInteger attempts = new AtomicInteger();
        PostgresConnectionFactory.ConnectionWork<Void> closesThePool = connection -> {
            pool.close();
            throw new SQLException("connection lost", SpyDatabase.CONNECTION_LOST);
        };

        SQLException failure = assertThrows(SQLException.class,
                () -> pool.withConnection(counting(attempts, closesThePool)));

        assertEquals("PostgreSQL connection pool is closed", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(1, attempts.get());
        assertEquals(1, database.opened());
        assertTrue(database.session(1).closed);
    }

    @Test
    void aWriteThatAlreadyRanIsNeverReplayed() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            pool.withConnection(query("SELECT 1"));
            AtomicInteger attempts = new AtomicInteger();
            PostgresConnectionFactory.ConnectionWork<Integer> writesThenLosesItsConnection = connection -> {
                update("UPDATE 1").execute(connection);
                database.session(1).broken = true;
                return query("SELECT 2").execute(connection);
            };

            SQLException failure = assertThrows(SQLException.class,
                    () -> pool.withConnection(counting(attempts, writesThenLosesItsConnection)));

            assertTrue(PostgresConnectionFactory.isConnectionFailure(failure));
            assertEquals(1, attempts.get());
            assertEquals(1, database.opened());
            assertEquals(List.of("1:SELECT 1", "1:UPDATE 1"), database.executed());
            assertEquals(new PostgresConnectionFactory.PoolStats(2, 0, 0, 0, 0L, 0, 0L), pool.poolStats());
        }
    }

    @Test
    void aSecondReadAfterACompletedOneIsNotReplayed() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            pool.withConnection(query("SELECT 1"));
            AtomicInteger attempts = new AtomicInteger();
            PostgresConnectionFactory.ConnectionWork<Integer> losesItsConnectionMidway = connection -> {
                query("SELECT 2").execute(connection);
                database.session(1).broken = true;
                return query("SELECT 3").execute(connection);
            };

            assertThrows(SQLException.class, () -> pool.withConnection(counting(attempts, losesItsConnectionMidway)));

            assertEquals(1, attempts.get(), "only the first statement of a call may be replayed");
            assertEquals(1, database.opened());
        }
    }

    @Test
    void aSecondStatementIsNotReplayedEvenWhenTheFirstOneFailed() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            pool.withConnection(query("SELECT 1"));
            database.session(1).nextStatementFailure = new SQLException("syntax", "42601");
            AtomicInteger attempts = new AtomicInteger();
            PostgresConnectionFactory.ConnectionWork<Integer> retriesByItself = connection -> {
                assertThrows(SQLException.class, () -> query("SELECT 2").execute(connection));
                database.session(1).broken = true;
                return query("SELECT 3").execute(connection);
            };

            assertThrows(SQLException.class, () -> pool.withConnection(counting(attempts, retriesByItself)));

            assertEquals(1, attempts.get());
            assertEquals(1, database.opened());
        }
    }

    @Test
    void aFirstWriteOutsideATransactionIsNotReplayedBecauseItsOutcomeIsUnknown() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            pool.withConnection(query("SELECT 1"));
            database.session(1).broken = true;
            AtomicInteger attempts = new AtomicInteger();

            SQLException failure = assertThrows(SQLException.class,
                    () -> pool.withConnection(counting(attempts, update("UPDATE 1"))));

            assertTrue(PostgresConnectionFactory.isConnectionFailure(failure));
            assertEquals(1, attempts.get());
            assertEquals(1, database.opened());
            assertEquals(7, pool.withConnection(query("SELECT 2")), "the next call gets a new connection");
            assertEquals(List.of("1:SELECT 1", "2:SELECT 2"), database.executed());
        }
    }

    @Test
    void aTransactionWhoseFirstStatementLosesItsConnectionIsReplayedOnceAndCommittedOnce() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            pool.withConnection(query("SELECT 1"));
            database.session(1).broken = true;
            AtomicInteger attempts = new AtomicInteger();

            pool.inTransaction(counting(attempts, update("INSERT 1")));

            assertEquals(2, attempts.get());
            assertEquals(List.of("1:SELECT 1", "2:INSERT 1"), database.executed());
            assertEquals(0, database.session(1).commits.get(), "nothing was committed on the lost session");
            assertEquals(1, database.session(2).commits.get());
            assertTrue(database.session(2).autoCommit, "the replayed transaction restores autocommit");
            assertEquals(new PostgresConnectionFactory.PoolStats(2, 0, 1, 1, 0L, 0, 0L), pool.poolStats());
        }
    }

    @Test
    void aTransactionThatAlreadyWroteIsNotReplayed() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            pool.withConnection(query("SELECT 1"));
            AtomicInteger attempts = new AtomicInteger();
            PostgresConnectionFactory.ConnectionWork<Integer> writesThenLosesItsConnection = connection -> {
                update("INSERT 1").execute(connection);
                database.session(1).broken = true;
                return update("INSERT 2").execute(connection);
            };

            assertThrows(SQLException.class,
                    () -> pool.inTransaction(counting(attempts, writesThenLosesItsConnection)));

            assertEquals(1, attempts.get());
            assertEquals(1, database.opened());
            assertEquals(0, database.session(1).commits.get());
        }
    }

    @Test
    void aCommitWhoseAcknowledgementIsLostIsReportedUncertainAndNeverReplayed() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            pool.withConnection(query("SELECT 1"));
            AtomicInteger attempts = new AtomicInteger();
            PostgresConnectionFactory.ConnectionWork<Void> losesItsConnectionBeforeCommit = connection -> {
                database.session(1).broken = true;
                return null;
            };

            assertThrows(CommitUncertainException.class,
                    () -> pool.inTransaction(counting(attempts, losesItsConnectionBeforeCommit)));

            assertEquals(1, attempts.get());
            assertEquals(1, database.opened());
            assertEquals(1, database.session(1).commits.get());
            assertTrue(database.session(1).closed);
        }
    }

    @Test
    void aFailureOnAConnectionThatWasJustOpenedOrJustValidatedIsNotReplayed() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            AtomicInteger attempts = new AtomicInteger();
            PostgresConnectionFactory.ConnectionWork<Integer> lost = connection -> {
                database.session(database.opened()).broken = true;
                return query("SELECT 1").execute(connection);
            };

            assertThrows(SQLException.class, () -> pool.withConnection(counting(attempts, lost)));
            assertEquals(1, attempts.get(), "a new connection that fails is an outage, not a stale connection");

            pool.withConnection(query("SELECT 2"));
            now.addAndGet(THRESHOLD_NANOS);
            assertThrows(SQLException.class, () -> pool.withConnection(counting(attempts, lost)));

            assertEquals(2, attempts.get(), "a connection validated a moment ago is not replayed either");
            assertEquals(1, database.session(2).validations.get());
            assertEquals(2, database.opened());
        }
    }

    @Test
    void aCallThatManagesTheConnectionItselfIsNotReplayed() throws Exception {
        try (PostgresConnectionFactory pool = pool(4)) {
            AtomicInteger attempts = new AtomicInteger();
            PostgresConnectionFactory.ConnectionWork<Integer> ownTransaction = connection -> {
                connection.setAutoCommit(false);
                return query("SELECT 2").execute(connection);
            };
            PostgresConnectionFactory.ConnectionWork<Integer> unwrappedStatement = connection -> {
                try (PreparedStatement statement = connection.prepareStatement("SELECT 2")) {
                    assertNotSame(statement, statement.unwrap(PreparedStatement.class));
                    statement.executeQuery();
                    return 7;
                }
            };

            for (PostgresConnectionFactory.ConnectionWork<Integer> work : List.of(ownTransaction, unwrappedStatement)) {
                pool.withConnection(query("SELECT 1"));
                database.session(database.opened()).broken = true;
                assertThrows(SQLException.class, () -> pool.withConnection(counting(attempts, work)));
            }

            assertEquals(2, attempts.get());
            assertEquals(2, database.opened());
        }
    }

    @Test
    void aScopedLeaseAndABoundDedicatedSessionAreNeverReplayed() throws Exception {
        try (PostgresConnectionFactory pool = pool(2)) {
            pool.withConnection(query("SELECT 1"));
            database.session(1).broken = true;
            AtomicInteger attempts = new AtomicInteger();

            PostgresConnectionFactory.ScopedConnectionLease scoped = pool.openScopedConnection();
            assertThrows(SQLException.class, () -> pool.withConnection(counting(attempts, query("SELECT 2"))));
            scoped.close();

            PostgresConnectionFactory.DedicatedConnectionLease dedicated = pool.openDedicatedConnection();
            PostgresConnectionFactory.DedicatedConnectionContext bound = pool.bindDedicatedConnection(dedicated);
            database.session(2).broken = true;
            assertThrows(SQLException.class, () -> pool.withConnection(counting(attempts, query("SELECT 3"))));
            bound.close();
            dedicated.close();

            assertEquals(2, attempts.get(), "a session the caller holds cannot be swapped under it");
            assertEquals(2, database.opened());
            assertTrue(database.session(1).closed);
            assertTrue(database.session(2).closed);
            assertEquals(new PostgresConnectionFactory.PoolStats(2, 0, 0, 0, 0L, 0, 0L), pool.poolStats());
        }
    }

    @Test
    void theHandleGivenToACallIsStableAndRevealsNothingAboutTheServer() throws Exception {
        try (PostgresConnectionFactory pool = pool(1)) {
            Set<Object> seen = new HashSet<>();

            Connection first = pool.withConnection(connection -> {
                seen.add(connection);
                try (Statement statement = connection.createStatement()) {
                    seen.add(statement);
                    assertSame(connection, statement.getConnection(), "a statement leads back to the handle");
                    assertTrue(seen.contains(statement));
                    assertFalse(statement.toString().contains("127.0.0.1"));
                }
                assertTrue(connection.isValid(1));
                return connection;
            });

            Connection again = pool.withConnection(connection -> connection);
            Connection driverConnection = database.session(1).connection;
            assertTrue(seen.contains(first));
            assertSame(first, again);
            assertEquals(first, again);
            assertNotSame(driverConnection, first);
            assertNotEquals(first, driverConnection);
            assertFalse(seen.contains(driverConnection));
            assertEquals("pooled PostgreSQL JDBC handle", first.toString());
        }
    }

    @Test
    void aConnectionReturnedWhileThePoolClosesIsClosedInsteadOfBeingLeftIdle() throws Exception {
        PostgresConnectionFactory pool = pool(2);
        pool.withConnection(connection -> {
            database.session(1).duringClearWarnings = pool::close;
            return null;
        });

        assertTrue(database.session(1).closed);
        assertEquals(new PostgresConnectionFactory.PoolStats(2, 0, 0, 0, 0L, 0, 0L), pool.poolStats());
    }

    @RepeatedTest(50)
    void concurrentCallsNeverShareAConnectionAndLeaveThePoolBalanced() throws Exception {
        int threads = 8;
        int poolSize = 3;
        try (PostgresConnectionFactory pool = pool(poolSize, Duration.ofSeconds(30))) {
            Set<Connection> inUse = ConcurrentHashMap.newKeySet();
            Queue<Throwable> violations = new ConcurrentLinkedQueue<>();
            PostgresConnectionFactory.ConnectionWork<Void> exclusive = connection -> {
                if (!inUse.add(connection)) violations.add(new AssertionError("connection shared by two calls"));
                try {
                    query("SELECT 1").execute(connection);
                    now.addAndGet(THRESHOLD_NANOS / 4);
                } finally {
                    inUse.remove(connection);
                }
                return null;
            };
            PostgresConnectionFactory.ConnectionWork<Void> failing = connection -> {
                exclusive.execute(connection);
                throw new IOException("work failed");
            };

            runConcurrently(threads, violations, iteration -> {
                if (iteration % 5 == 4) {
                    assertThrows(IOException.class, () -> pool.withConnection(failing));
                } else {
                    pool.withConnection(exclusive);
                }
            });

            assertEquals(List.of(), List.copyOf(violations));
            PostgresConnectionFactory.PoolStats stats = pool.poolStats();
            assertEquals(0, stats.leased());
            assertEquals(stats.idle(), stats.physical(), "every open connection is back at rest");
            assertTrue(stats.physical() <= poolSize, "physical=" + stats.physical());
            assertEquals(stats.physical(), openSessions());
            assertEquals(0L, stats.acquisitionTimeouts());
        }
    }

    @RepeatedTest(50)
    void closingThePoolUnderLoadLeavesNoConnectionOpen() throws Exception {
        PostgresConnectionFactory pool = pool(3, Duration.ofSeconds(30));
        Queue<Throwable> violations = new ConcurrentLinkedQueue<>();
        CountDownLatch halfway = new CountDownLatch(1);
        Thread closer = Thread.ofPlatform().start(() -> {
            try {
                halfway.await();
                pool.close();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                violations.add(interrupted);
            }
        });

        runConcurrently(6, violations, iteration -> {
            if (iteration == 10) halfway.countDown();
            try {
                pool.withConnection(query("SELECT 1"));
            } catch (SQLException refused) {
                assertEquals("PostgreSQL connection pool is closed", refused.getMessage());
            }
        });
        closer.join();

        assertEquals(List.of(), List.copyOf(violations));
        assertEquals(0, openSessions(), "a connection returned during the close must not stay idle forever");
        assertEquals(new PostgresConnectionFactory.PoolStats(3, 0, 0, 0, 0L, 0, 0L), pool.poolStats());
    }

    private long openSessions() {
        long open = 0;
        for (int ordinal = 1; ordinal <= database.opened(); ordinal++) {
            if (!database.session(ordinal).closed) open++;
        }
        return open;
    }

    /** Starts {@code threads} threads together; each runs {@code action} for iterations 0 to 19. */
    private static void runConcurrently(int threads, Queue<Throwable> violations, IterationAction action)
            throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        Thread[] workers = new Thread[threads];
        for (int index = 0; index < threads; index++) {
            workers[index] = Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    for (int iteration = 0; iteration < 20; iteration++) action.run(iteration);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    violations.add(interrupted);
                } catch (SQLException | IOException | RuntimeException | AssertionError failure) {
                    violations.add(failure);
                }
            });
        }
        start.countDown();
        for (Thread worker : workers) worker.join();
    }

    private PostgresConnectionFactory pool(int maximumSize) throws IOException {
        return pool(maximumSize, Duration.ofMillis(200));
    }

    private PostgresConnectionFactory pool(int maximumSize, Duration acquireTimeout) throws IOException {
        StorageBackendConfiguration configuration = new StorageBackendConfiguration(
                "postgresql", home, LOOPBACK_URL, "minos", FIXTURE_PASSWORD, "minos");
        return new PostgresConnectionFactory(configuration, maximumSize, acquireTimeout, 10, 120, database, now::get);
    }

    /** A read: one prepared statement executed by {@code executeQuery}; answers 7. */
    private static PostgresConnectionFactory.ConnectionWork<Integer> query(String sql) {
        return connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.executeQuery();
                return 7;
            }
        };
    }

    /** A write: one statement executed by {@code executeUpdate}; answers 7. */
    private static PostgresConnectionFactory.ConnectionWork<Integer> update(String sql) {
        return connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(sql);
                return 7;
            }
        };
    }

    private static <T> PostgresConnectionFactory.ConnectionWork<T> counting(
            AtomicInteger attempts, PostgresConnectionFactory.ConnectionWork<T> work
    ) {
        return connection -> {
            attempts.incrementAndGet();
            return work.execute(connection);
        };
    }

    @FunctionalInterface
    private interface IterationAction {
        void run(int iteration) throws SQLException, IOException;
    }
}
