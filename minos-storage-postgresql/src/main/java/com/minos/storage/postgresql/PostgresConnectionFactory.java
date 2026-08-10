package com.minos.storage.postgresql;

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

/** Small bounded JDBC pool owned by one PostgreSQL storage backend. */
final class PostgresConnectionFactory implements AutoCloseable {
    static final int DEFAULT_MAX_POOL_SIZE = 8;
    static final Duration DEFAULT_ACQUIRE_TIMEOUT = Duration.ofSeconds(10);
    private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
    private static final int DEFAULT_SOCKET_TIMEOUT_SECONDS = 120;

    private final String url;
    private final String user;
    private final String password;
    private final String schema;
    private final int maxPoolSize;
    private final Duration acquireTimeout;
    private final int connectTimeoutSeconds;
    private final int socketTimeoutSeconds;
    private final Semaphore leases;
    private final ConcurrentLinkedQueue<Connection> idle = new ConcurrentLinkedQueue<>();
    private final AtomicInteger physicalConnections = new AtomicInteger();
    private final AtomicInteger leasedConnections = new AtomicInteger();
    private final AtomicLong acquisitionTimeouts = new AtomicLong();
    private volatile boolean closed;

    PostgresConnectionFactory(StorageBackendConfiguration configuration) throws IOException {
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
        Objects.requireNonNull(configuration, "configuration");
        if (!configuration.postgresql()) throw new IOException("PostgreSQL configuration required");
        this.url = require(configuration.postgresUrl(), "MINOS_POSTGRES_URL");
        this.user = require(configuration.postgresUser(), "MINOS_POSTGRES_USER");
        this.password = require(configuration.postgresPassword(), "MINOS_POSTGRES_PASSWORD");
        this.schema = configuration.postgresSchema();
        if (!url.startsWith("jdbc:postgresql://")) throw new IOException("MINOS_POSTGRES_URL must use jdbc:postgresql://");
        if (maxPoolSize < 1 || maxPoolSize > 128) throw new IllegalArgumentException("maxPoolSize must be between 1 and 128");
        this.acquireTimeout = Objects.requireNonNull(acquireTimeout, "acquireTimeout");
        if (acquireTimeout.isZero() || acquireTimeout.isNegative()) throw new IllegalArgumentException("acquireTimeout must be positive");
        if (connectTimeoutSeconds < 1 || socketTimeoutSeconds < 1) throw new IllegalArgumentException("JDBC timeouts must be positive");
        this.maxPoolSize = maxPoolSize;
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.socketTimeoutSeconds = socketTimeoutSeconds;
        this.leases = new Semaphore(maxPoolSize, true);
    }

    <T> T withConnection(ConnectionWork<T> work) throws SQLException, IOException {
        Objects.requireNonNull(work, "work");
        Connection connection = borrow();
        try {
            return work.execute(connection);
        } finally {
            release(connection);
        }
    }

    <T> T inTransaction(ConnectionWork<T> work) throws SQLException, IOException {
        Objects.requireNonNull(work, "work");
        return withConnection(connection -> {
            connection.setAutoCommit(false);
            try {
                T result = work.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException | IOException | RuntimeException exception) {
                rollbackPreserving(connection, exception);
                throw exception;
            }
        });
    }

    String schema() { return schema; }

    PoolStats poolStats() {
        return new PoolStats(
                maxPoolSize,
                leasedConnections.get(),
                idle.size(),
                physicalConnections.get(),
                acquisitionTimeouts.get());
    }

    @Override
    public void close() {
        closed = true;
        Connection connection;
        while ((connection = idle.poll()) != null) closePhysical(connection);
    }

    @FunctionalInterface
    interface ConnectionWork<T> {
        T execute(Connection connection) throws SQLException, IOException;
    }

    private Connection borrow() throws SQLException {
        if (closed) throw new SQLException("PostgreSQL connection pool is closed");
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
        if (closed) {
            leases.release();
            throw new SQLException("PostgreSQL connection pool is closed");
        }
        try {
            Connection connection;
            while ((connection = idle.poll()) != null) {
                if (usable(connection)) {
                    leasedConnections.incrementAndGet();
                    return connection;
                }
                closePhysical(connection);
            }
            Connection created = openPhysical();
            leasedConnections.incrementAndGet();
            return created;
        } catch (SQLException | RuntimeException exception) {
            leases.release();
            throw exception;
        }
    }

    private void release(Connection connection) {
        try {
            if (!usable(connection)) {
                closePhysical(connection);
                return;
            }
            try {
                if (!connection.getAutoCommit()) {
                    connection.rollback();
                    connection.setAutoCommit(true);
                }
                connection.clearWarnings();
            } catch (SQLException exception) {
                closePhysical(connection);
                return;
            }
            if (closed) closePhysical(connection);
            else idle.offer(connection);
        } finally {
            leasedConnections.decrementAndGet();
            leases.release();
        }
    }

    private Connection openPhysical() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("currentSchema", schema + ",public");
        properties.setProperty("connectTimeout", Integer.toString(connectTimeoutSeconds));
        properties.setProperty("socketTimeout", Integer.toString(socketTimeoutSeconds));
        properties.setProperty("tcpKeepAlive", "true");
        properties.setProperty("ApplicationName", "MINOS");
        Connection connection = DriverManager.getConnection(url, properties);
        physicalConnections.incrementAndGet();
        return connection;
    }

    private static boolean usable(Connection connection) {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException exception) {
            return false;
        }
    }

    private void closePhysical(Connection connection) {
        if (connection == null) return;
        try {
            if (!connection.isClosed()) connection.close();
        } catch (SQLException ignored) {
            // Closing a broken pooled connection is best effort; it will never be reused.
        } finally {
            physicalConnections.updateAndGet(value -> Math.max(0, value - 1));
        }
    }

    private static void rollbackPreserving(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static String require(String value, String name) throws IOException {
        if (value == null || value.isBlank()) throw new IOException("missing required PostgreSQL setting: " + name);
        return value.trim();
    }

    record PoolStats(int maximumSize, int leased, int idle, int physical, long acquisitionTimeouts) { }
}
