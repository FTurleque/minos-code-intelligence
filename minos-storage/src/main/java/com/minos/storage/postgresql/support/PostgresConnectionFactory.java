package com.minos.storage.postgresql.support;

import com.minos.io.CommitUncertainException;
import com.minos.storage.StorageBackendConfiguration;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Small bounded JDBC pool owned by one PostgreSQL storage backend. */
public final class PostgresConnectionFactory implements AutoCloseable {
    static final int DEFAULT_MAX_POOL_SIZE = 8;
    static final Duration DEFAULT_ACQUIRE_TIMEOUT = Duration.ofSeconds(10);
    private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
    private static final int DEFAULT_SOCKET_TIMEOUT_SECONDS = 120;
    private static final int VALIDATION_TIMEOUT_SECONDS = 5;
    private static final String POOL_CLOSED = "PostgreSQL connection pool is closed";
    /**
     * A pooled connection idle for less than this is handed out without a validation round trip; at or beyond
     * it, the connection is validated once before being handed out.
     */
    static final Duration IDLE_VALIDATION_THRESHOLD = Duration.ofSeconds(30);

    private final String url;
    private final String user;
    private final String password;
    private final String schema;
    private final int maxPoolSize;
    private final Duration acquireTimeout;
    private final int connectTimeoutSeconds;
    private final int socketTimeoutSeconds;
    private final ConnectionSource connectionSource;
    private final LongSupplier nanoClock;
    private final Semaphore leases;
    private final Semaphore dedicatedLeases;
    private final ConcurrentLinkedQueue<PooledConnection> idle = new ConcurrentLinkedQueue<>();
    private final AtomicInteger physicalConnections = new AtomicInteger();
    private final AtomicInteger leasedConnections = new AtomicInteger();
    private final AtomicInteger dedicatedConnections = new AtomicInteger();
    private final AtomicLong acquisitionTimeouts = new AtomicLong();
    private final AtomicLong dedicatedAcquisitionTimeouts = new AtomicLong();
    private final ThreadLocal<LeaseContext> currentLease = new ThreadLocal<>();
    private volatile boolean closed;

    public PostgresConnectionFactory(StorageBackendConfiguration configuration) throws IOException {
        this(configuration, DEFAULT_MAX_POOL_SIZE, DEFAULT_ACQUIRE_TIMEOUT,
                DEFAULT_CONNECT_TIMEOUT_SECONDS, DEFAULT_SOCKET_TIMEOUT_SECONDS);
    }

    PostgresConnectionFactory(
            StorageBackendConfiguration configuration,
            int maxPoolSize,
            Duration acquireTimeout,
            int connectTimeoutSeconds,
            int socketTimeoutSeconds
    ) throws IOException {
        this(configuration, maxPoolSize, acquireTimeout, connectTimeoutSeconds, socketTimeoutSeconds,
                DriverManager::getConnection, System::nanoTime);
    }

    /**
     * Canonical constructor. {@code connectionSource} and {@code nanoClock} are seams for pool tests that run
     * without a PostgreSQL server; production always uses the JDBC driver manager and the monotonic JVM clock.
     */
    PostgresConnectionFactory(
            StorageBackendConfiguration configuration,
            int maxPoolSize,
            Duration acquireTimeout,
            int connectTimeoutSeconds,
            int socketTimeoutSeconds,
            ConnectionSource connectionSource,
            LongSupplier nanoClock
    ) throws IOException {
        Objects.requireNonNull(configuration, "configuration");
        if (!configuration.postgresql()) throw new IOException("PostgreSQL configuration required");
        this.url = require(configuration.postgresUrl(), "MINOS_POSTGRES_URL");
        this.user = require(configuration.postgresUser(), "MINOS_POSTGRES_USER");
        this.password = require(configuration.postgresPassword(), "MINOS_POSTGRES_PASSWORD");
        this.schema = configuration.postgresSchema();
        PostgresJdbcUrlPolicy.validate(url, configuration.postgresManaged());
        if (maxPoolSize < 1 || maxPoolSize > 128) {
            throw new IllegalArgumentException("maxPoolSize must be between 1 and 128");
        }
        this.acquireTimeout = Objects.requireNonNull(acquireTimeout, "acquireTimeout");
        if (acquireTimeout.isZero() || acquireTimeout.isNegative()) {
            throw new IllegalArgumentException("acquireTimeout must be positive");
        }
        if (connectTimeoutSeconds < 1 || socketTimeoutSeconds < 1) {
            throw new IllegalArgumentException("JDBC timeouts must be positive");
        }
        this.maxPoolSize = maxPoolSize;
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.socketTimeoutSeconds = socketTimeoutSeconds;
        this.connectionSource = Objects.requireNonNull(connectionSource, "connectionSource");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.leases = new Semaphore(maxPoolSize, true);
        // Lifecycle advisory locks are long-lived control-plane resources. They deliberately use a
        // separate bounded connection budget so lock waiters cannot starve ordinary query traffic.
        this.dedicatedLeases = new Semaphore(maxPoolSize, true);
    }

    /**
     * Runs {@code work} on a pooled connection, or on the connection the current thread already leases.
     *
     * <p>A connection that rested for less than {@link #IDLE_VALIDATION_THRESHOLD} is handed out without a
     * validation round trip. If such a connection turns out to be lost (SQLState class {@code 08}) before the
     * call completed any statement, the call is replayed <strong>once</strong> on a newly opened connection.
     * It is never replayed after a statement completed, after a write sent outside a pool-managed
     * transaction, after a commit was attempted, or when the call managed the connection itself;
     * {@code work} must therefore not depend on being invoked exactly once before its first statement.</p>
     */
    public <T> T withConnection(ConnectionWork<T> work) throws SQLException, IOException {
        Objects.requireNonNull(work, "work");
        LeaseContext nested = currentLease.get();
        if (nested != null) return executeOnLease(nested, work);

        LeaseContext context = LeaseContext.pooled(borrow());
        try {
            return executeTopLevel(context, work);
        } catch (SQLException failure) {
            if (!context.replayable(failure)) throw failure;
            return replayOnNewConnection(work, failure);
        } finally {
            leases.release();
        }
    }

    private <T> T executeTopLevel(LeaseContext context, ConnectionWork<T> work) throws SQLException, IOException {
        boolean completed = false;
        currentLease.set(context);
        try {
            T result = executeOnLease(context, work);
            completed = true;
            return result;
        } finally {
            currentLease.remove();
            // A call that did not complete leaves a connection in an unknown state: it is never pooled again.
            if (!completed) context.reusable = false;
            checkIn(context);
        }
    }

    /** Second and last attempt of a call, under the lease of the first one and on a connection opened for it. */
    private <T> T replayOnNewConnection(ConnectionWork<T> work, SQLException firstFailure)
            throws SQLException, IOException {
        try {
            if (closed) throw new SQLException(POOL_CLOSED);
            return executeTopLevel(LeaseContext.pooled(openPooledPhysical()), work);
        } catch (SQLException | IOException | RuntimeException secondFailure) {
            if (secondFailure != firstFailure) secondFailure.addSuppressed(firstFailure);
            throw secondFailure;
        }
    }

    public <T> T inTransaction(ConnectionWork<T> work) throws SQLException, IOException {
        Objects.requireNonNull(work, "work");
        LeaseContext nested = currentLease.get();
        if (nested != null && nested.transactionDepth > 0) {
            nested.transactionDepth++;
            try {
                return work.execute(nested.connection);
            } catch (SQLException | IOException | RuntimeException exception) {
                nested.rollbackOnly = true;
                if (exception instanceof SQLException sql && isConnectionFailure(sql)) nested.reusable = false;
                throw exception;
            } finally {
                nested.transactionDepth--;
            }
        }
        return withConnection(connection -> executeTransaction(currentLease.get(), work));
    }

    /** Compatibility scoped lease for short-lived code that intentionally reuses the pool connection. */
    ScopedConnectionLease openScopedConnection() throws SQLException {
        if (currentLease.get() != null) {
            throw new SQLException("cannot open a scoped PostgreSQL connection inside another connection lease");
        }
        LeaseContext context = LeaseContext.pooled(borrow());
        currentLease.set(context);
        return new ScopedConnectionLease(context, Thread.currentThread());
    }

    /**
     * Opens a bounded connection outside the query pool. This is reserved for session advisory
     * lifecycle locks whose lifetime can span provider execution and must not consume query capacity.
     */
    public DedicatedConnectionLease openDedicatedConnection() throws SQLException {
        if (closed) throw new SQLException(POOL_CLOSED);
        final boolean acquired;
        try {
            acquired = dedicatedLeases.tryAcquire(acquireTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SQLException("interrupted while acquiring dedicated PostgreSQL connection", exception);
        }
        if (!acquired) {
            dedicatedAcquisitionTimeouts.incrementAndGet();
            throw new SQLException("PostgreSQL dedicated connection budget exhausted after " + acquireTimeout);
        }
        if (closed) {
            dedicatedLeases.release();
            throw new SQLException(POOL_CLOSED);
        }
        try {
            Connection connection = openRawConnection();
            dedicatedConnections.incrementAndGet();
            return new DedicatedConnectionLease(connection, Thread.currentThread());
        } catch (SQLException | RuntimeException exception) {
            dedicatedLeases.release();
            throw exception;
        }
    }

    /**
     * Binds a dedicated lifecycle session to the normal connection context for the current thread.
     * Every nested store operation then reuses the exact session that owns the advisory lock. A
     * connection loss therefore fails closed instead of silently borrowing another pooled session.
     */
    public DedicatedConnectionContext bindDedicatedConnection(DedicatedConnectionLease lease) {
        DedicatedConnectionLease dedicated = Objects.requireNonNull(lease, "lease");
        if (currentLease.get() != null) {
            throw new IllegalStateException("cannot bind a PostgreSQL dedicated connection inside another lease");
        }
        LeaseContext context = LeaseContext.dedicated(dedicated.connection());
        currentLease.set(context);
        return new DedicatedConnectionContext(dedicated, context, Thread.currentThread());
    }

    private <T> T executeTransaction(LeaseContext context, ConnectionWork<T> work) throws SQLException, IOException {
        if (context == null) throw new SQLException("PostgreSQL transaction has no active connection lease");
        // The pool drives the transaction on the driver connection; only the caller's work goes through the handle.
        Connection connection = context.raw;
        boolean previousAutoCommit = connection.getAutoCommit();
        Exception primaryFailure = null;
        context.transactionDepth = 1;
        context.rollbackOnly = false;
        context.activity.transactional(true);
        try {
            if (previousAutoCommit) connection.setAutoCommit(false);
            T result = work.execute(context.connection);
            if (context.rollbackOnly) {
                throw new SQLException("PostgreSQL transaction was marked rollback-only by nested work");
            }
            // Past this point the outcome belongs to the server: the call must never be replayed.
            context.activity.forbidReplay();
            commitTransaction(connection);
            return result;
        } catch (CommitUncertainException uncertain) {
            primaryFailure = uncertain;
            // Once COMMIT was sent and the connection-level acknowledgement was lost, rollback
            // cannot establish the outcome and could mislead callers. Discard the connection and
            // force the application to re-observe its authoritative commit record instead.
            context.reusable = false;
            throw uncertain;
        } catch (SQLException | IOException | RuntimeException exception) {
            primaryFailure = exception;
            if (exception instanceof SQLException sql && isConnectionFailure(sql)) context.reusable = false;
            rollbackPreserving(connection, exception);
            throw exception;
        } finally {
            context.transactionDepth = 0;
            context.rollbackOnly = false;
            context.activity.transactional(false);
            if (previousAutoCommit) {
                try {
                    if (!connection.getAutoCommit()) connection.setAutoCommit(true);
                } catch (SQLException restoreFailure) {
                    context.reusable = false;
                    if (primaryFailure != null) primaryFailure.addSuppressed(restoreFailure);
                }
            }
        }
    }

    static void commitTransaction(Connection connection) throws SQLException, CommitUncertainException {
        try {
            connection.commit();
        } catch (SQLException failure) {
            if (isConnectionFailure(failure)) {
                throw new CommitUncertainException(
                        "PostgreSQL transaction commit acknowledgement was lost", failure);
            }
            throw failure;
        }
    }

    private <T> T executeOnLease(LeaseContext context, ConnectionWork<T> work) throws SQLException, IOException {
        try {
            return work.execute(context.connection);
        } catch (SQLException exception) {
            if (isConnectionFailure(exception)) context.reusable = false;
            throw exception;
        }
    }

    String schema() {
        return schema;
    }

    public Duration acquireTimeout() {
        return acquireTimeout;
    }

    public PoolStats poolStats() {
        return new PoolStats(
                maxPoolSize,
                leasedConnections.get(),
                idle.size(),
                physicalConnections.get(),
                acquisitionTimeouts.get(),
                dedicatedConnections.get(),
                dedicatedAcquisitionTimeouts.get());
    }

    @Override
    public void close() {
        closed = true;
        PooledConnection pooled;
        while ((pooled = idle.poll()) != null) closePooledPhysical(pooled.raw());
    }

    @FunctionalInterface
    public interface ConnectionWork<T> {
        T execute(Connection connection) throws SQLException, IOException;
    }

    /** Opens one physical JDBC connection from the validated URL and the connection properties. */
    @FunctionalInterface
    interface ConnectionSource {
        Connection connect(String url, Properties properties) throws SQLException;
    }

    final class ScopedConnectionLease implements AutoCloseable {
        private final Connection connection;
        private final LeaseContext context;
        private final Thread owner;
        // Written by the owner thread only; volatile because close() reads it before checking the caller.
        private volatile boolean closedLease;

        private ScopedConnectionLease(LeaseContext context, Thread owner) {
            this.connection = context.connection;
            this.context = context;
            this.owner = owner;
        }

        Connection connection() {
            requireOwner();
            if (closedLease) throw new IllegalStateException("PostgreSQL scoped connection lease is closed");
            return connection;
        }

        void invalidate() {
            requireOwner();
            context.reusable = false;
        }

        @Override
        public void close() {
            if (closedLease) return;
            requireOwner();
            closedLease = true;
            LeaseContext active = currentLease.get();
            if (active != context) {
                context.reusable = false;
                throw new IllegalStateException("PostgreSQL scoped connection lease lost thread ownership context");
            }
            currentLease.remove();
            try {
                checkIn(context);
            } finally {
                leases.release();
            }
        }

        private void requireOwner() {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("PostgreSQL scoped connection lease must be used by its owner thread");
            }
        }
    }

    public final class DedicatedConnectionLease implements AutoCloseable {
        private final Connection connection;
        private final Thread owner;
        // Written by the owner thread only; volatile so that a read from another thread, as close() does before
        // checking its caller, sees the last write.
        private volatile boolean reusable = true;
        private volatile boolean closedLease;

        private DedicatedConnectionLease(Connection connection, Thread owner) {
            this.connection = connection;
            this.owner = owner;
        }

        public Connection connection() {
            requireOwner();
            if (closedLease) throw new IllegalStateException("PostgreSQL dedicated connection lease is closed");
            return connection;
        }

        public void invalidate() {
            requireOwner();
            reusable = false;
        }

        @Override
        public void close() {
            if (closedLease) return;
            requireOwner();
            closedLease = true;
            try {
                if (reusable && !connection.isClosed()) connection.close();
                else closeQuietly(connection);
            } catch (SQLException ignored) {
                closeQuietly(connection);
            } finally {
                dedicatedConnections.decrementAndGet();
                dedicatedLeases.release();
            }
        }

        private void requireOwner() {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("PostgreSQL dedicated connection lease must be used by its owner thread");
            }
        }
    }

    public final class DedicatedConnectionContext implements AutoCloseable {
        private final DedicatedConnectionLease lease;
        private final LeaseContext context;
        private final Thread owner;
        private boolean closedContext;

        private DedicatedConnectionContext(
                DedicatedConnectionLease lease,
                LeaseContext context,
                Thread owner
        ) {
            this.lease = lease;
            this.context = context;
            this.owner = owner;
        }

        @Override
        public void close() {
            requireOwner();
            if (closedContext) return;
            if (currentLease.get() != context) {
                context.reusable = false;
                throw new IllegalStateException("PostgreSQL dedicated connection lost thread binding context");
            }
            if (context.transactionDepth != 0) {
                context.reusable = false;
                throw new IllegalStateException("cannot unbind PostgreSQL dedicated connection during a transaction");
            }
            closedContext = true;
            currentLease.remove();
            if (!context.reusable) lease.invalidate();
        }

        private void requireOwner() {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("PostgreSQL dedicated connection context must be released by its owner thread");
            }
        }
    }

    /** Takes one lease of the query pool and a connection for it; the caller owns both. */
    private PooledConnection borrow() throws SQLException {
        if (closed) throw new SQLException(POOL_CLOSED);
        final boolean acquired;
        try {
            acquired = leases.tryAcquire(acquireTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SQLException("interrupted while acquiring PostgreSQL connection", exception);
        }
        if (!acquired) {
            acquisitionTimeouts.incrementAndGet();
            throw new SQLException("PostgreSQL connection pool exhausted after " + acquireTimeout);
        }
        try {
            if (closed) throw new SQLException(POOL_CLOSED);
            PooledConnection pooled;
            while ((pooled = idle.poll()) != null) {
                if (handOutFromRest(pooled)) return pooled;
                closePooledPhysical(pooled.raw());
            }
            return openPooledPhysical();
        } catch (SQLException | RuntimeException exception) {
            leases.release();
            throw exception;
        }
    }

    /**
     * Decides whether a connection taken from rest can be handed out. Being closed is read locally; the
     * validation round trip is paid only at or beyond {@link #IDLE_VALIDATION_THRESHOLD}.
     */
    private boolean handOutFromRest(PooledConnection pooled) {
        boolean validate = pooled.idleNanos(nanoClock.getAsLong()) >= IDLE_VALIDATION_THRESHOLD.toNanos();
        try {
            Connection connection = pooled.raw();
            if (connection.isClosed()) return false;
            if (validate && !connection.isValid(Math.min(connectTimeoutSeconds, VALIDATION_TIMEOUT_SECONDS))) {
                return false;
            }
        } catch (SQLException | RuntimeException _) {
            return false;
        }
        pooled.leasedWithoutValidation(!validate);
        leasedConnections.incrementAndGet();
        return true;
    }

    /**
     * Returns the connection of a finished lease: back to rest when it is provably clean, closed otherwise.
     * Every check is local to the driver; no statement is sent.
     */
    private void checkIn(LeaseContext context) {
        PooledConnection pooled = context.pooled;
        try {
            if (!context.reusable || !cleanForRest(pooled.raw())) {
                closePooledPhysical(pooled.raw());
                return;
            }
            pooled.restingSince(nanoClock.getAsLong());
            idle.offer(pooled);
            // close() raises the flag before draining the queue: either it drains this connection, or the
            // flag is visible here and the connection is taken back and closed. It can never stay idle.
            if (closed && idle.remove(pooled)) closePooledPhysical(pooled.raw());
        } finally {
            leasedConnections.decrementAndGet();
        }
    }

    private static boolean cleanForRest(Connection connection) {
        try {
            if (connection.isClosed() || !connection.getAutoCommit()) return false;
            connection.clearWarnings();
            return true;
        } catch (SQLException | RuntimeException exception) {
            return false;
        }
    }

    /** Opens a connection for the lease the caller holds; a new connection needs no validation. */
    private PooledConnection openPooledPhysical() throws SQLException {
        PooledConnection pooled = new PooledConnection(openRawConnection());
        physicalConnections.incrementAndGet();
        leasedConnections.incrementAndGet();
        return pooled;
    }

    private Connection openRawConnection() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("currentSchema", schema + ",public");
        properties.setProperty("connectTimeout", Integer.toString(connectTimeoutSeconds));
        properties.setProperty("socketTimeout", Integer.toString(socketTimeoutSeconds));
        properties.setProperty("tcpKeepAlive", "true");
        properties.setProperty("ApplicationName", "MINOS");
        return connectionSource.connect(url, properties);
    }

    private void closePooledPhysical(Connection connection) {
        if (connection == null) return;
        try {
            if (!connection.isClosed()) connection.close();
        } catch (SQLException ignored) {
            // Closing a broken pooled connection is best effort; it will never be reused.
        } finally {
            physicalConnections.updateAndGet(value -> Math.max(0, value - 1));
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Best effort for a connection that is already unusable.
        }
    }

    static boolean isConnectionFailure(SQLException exception) {
        for (SQLException current = exception; current != null; current = current.getNextException()) {
            String state = current.getSQLState();
            if (state != null && state.startsWith("08")) return true;
        }
        return false;
    }

    private static void rollbackPreserving(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static String require(String value, String name) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException("missing required PostgreSQL setting: " + name);
        }
        return value.trim();
    }

    private static final class LeaseContext {
        /** What the caller's work receives: the tracking handle of a pooled connection, or a dedicated session. */
        private final Connection connection;
        /** The driver connection the pool itself drives. */
        private final Connection raw;
        /** Null when the lease is a bound dedicated session, which the query pool does not own. */
        private final PooledConnection pooled;
        private final PooledConnection.LeaseActivity activity;
        private boolean reusable = true;
        private int transactionDepth;
        private boolean rollbackOnly;

        private LeaseContext(
                Connection connection, Connection raw, PooledConnection pooled, PooledConnection.LeaseActivity activity
        ) {
            this.connection = connection;
            this.raw = raw;
            this.pooled = pooled;
            this.activity = activity;
        }

        private static LeaseContext pooled(PooledConnection pooled) {
            PooledConnection.LeaseActivity activity = new PooledConnection.LeaseActivity();
            return new LeaseContext(pooled.lease(activity), pooled.raw(), pooled, activity);
        }

        private static LeaseContext dedicated(Connection session) {
            return new LeaseContext(session, session, null, new PooledConnection.LeaseActivity());
        }

        /**
         * A connection loss may be answered by one replay only when the connection came from rest without
         * validation and the call did nothing yet that a replay could repeat.
         */
        private boolean replayable(SQLException failure) {
            return pooled.leasedWithoutValidation() && isConnectionFailure(failure) && activity.replayAllowed();
        }
    }

    public record PoolStats(
            int maximumSize,
            int leased,
            int idle,
            int physical,
            long acquisitionTimeouts,
            int dedicated,
            long dedicatedAcquisitionTimeouts
    ) {
    }
}
