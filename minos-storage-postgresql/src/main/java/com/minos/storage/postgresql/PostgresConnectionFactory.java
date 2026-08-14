package com.minos.storage.postgresql;

import com.minos.storage.StorageBackendConfiguration;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
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
    private static final int VALIDATION_TIMEOUT_SECONDS = 5;
    private static final String MANAGED_DOCKER_HOST = "minos-postgres";
    private static final Set<String> ALLOWED_URL_PARAMETERS = Set.of("sslmode");
    private static final Set<String> ALLOWED_SSL_MODES = Set.of(
            "disable", "allow", "prefer", "require", "verify-ca", "verify-full");

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
    private final ThreadLocal<LeaseContext> currentLease = new ThreadLocal<>();
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
        validateJdbcUrl(url, configuration.postgresManaged());
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
        this.leases = new Semaphore(maxPoolSize, true);
    }

    <T> T withConnection(ConnectionWork<T> work) throws SQLException, IOException {
        Objects.requireNonNull(work, "work");
        LeaseContext nested = currentLease.get();
        if (nested != null) return executeOnLease(nested, work);

        Connection connection = borrow();
        LeaseContext context = new LeaseContext(connection);
        currentLease.set(context);
        try {
            return executeOnLease(context, work);
        } finally {
            currentLease.remove();
            release(connection, context.reusable);
        }
    }

    <T> T inTransaction(ConnectionWork<T> work) throws SQLException, IOException {
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

    /**
     * Reserves one physical/pool connection on the current thread until close(). Nested store
     * operations automatically reuse it through currentLease, which allows a session advisory lock
     * to span multiple short transactions without keeping one database transaction open.
     */
    ScopedConnectionLease openScopedConnection() throws SQLException {
        if (currentLease.get() != null) {
            throw new SQLException("cannot open a scoped PostgreSQL connection inside another connection lease");
        }
        Connection connection = borrow();
        LeaseContext context = new LeaseContext(connection);
        currentLease.set(context);
        return new ScopedConnectionLease(connection, context, Thread.currentThread());
    }

    private <T> T executeTransaction(LeaseContext context, ConnectionWork<T> work) throws SQLException, IOException {
        if (context == null) throw new SQLException("PostgreSQL transaction has no active connection lease");
        Connection connection = context.connection;
        boolean previousAutoCommit = connection.getAutoCommit();
        Exception primaryFailure = null;
        context.transactionDepth = 1;
        context.rollbackOnly = false;
        try {
            if (previousAutoCommit) connection.setAutoCommit(false);
            T result = work.execute(connection);
            if (context.rollbackOnly) {
                throw new SQLException("PostgreSQL transaction was marked rollback-only by nested work");
            }
            connection.commit();
            return result;
        } catch (SQLException | IOException | RuntimeException exception) {
            primaryFailure = exception;
            if (exception instanceof SQLException sql && isConnectionFailure(sql)) context.reusable = false;
            rollbackPreserving(connection, exception);
            throw exception;
        } finally {
            context.transactionDepth = 0;
            context.rollbackOnly = false;
            if (previousAutoCommit) {
                try {
                    if (!connection.getAutoCommit()) connection.setAutoCommit(true);
                } catch (SQLException restoreFailure) {
                    context.reusable = false;
                    if (primaryFailure != null) primaryFailure.addSuppressed(restoreFailure);
                    else throw restoreFailure;
                }
            }
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

    Duration acquireTimeout() {
        return acquireTimeout;
    }

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

    final class ScopedConnectionLease implements AutoCloseable {
        private final Connection connection;
        private final LeaseContext context;
        private final Thread owner;
        private boolean closedLease;

        private ScopedConnectionLease(Connection connection, LeaseContext context, Thread owner) {
            this.connection = connection;
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
            release(connection, context.reusable);
        }

        private void requireOwner() {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("PostgreSQL scoped connection lease must be used by its owner thread");
            }
        }
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

    private void release(Connection connection, boolean reusable) {
        try {
            if (!reusable || !usable(connection)) {
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

    private boolean usable(Connection connection) {
        try {
            return connection != null
                    && !connection.isClosed()
                    && connection.isValid(Math.min(connectTimeoutSeconds, VALIDATION_TIMEOUT_SECONDS));
        } catch (SQLException | RuntimeException exception) {
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

    static boolean isConnectionFailure(SQLException exception) {
        for (SQLException current = exception; current != null; current = current.getNextException()) {
            String state = current.getSQLState();
            if (state != null && state.startsWith("08")) return true;
        }
        return false;
    }

    private static void validateJdbcUrl(String value, boolean managed) throws IOException {
        if (!value.startsWith("jdbc:postgresql://")) {
            throw new IOException("MINOS_POSTGRES_URL must use jdbc:postgresql://");
        }
        final URI uri;
        try {
            uri = new URI(value.substring("jdbc:".length()));
        } catch (URISyntaxException exception) {
            throw new IOException("MINOS_POSTGRES_URL is invalid", exception);
        }
        if (uri.getUserInfo() != null) {
            throw new IOException("MINOS_POSTGRES_URL must not contain user-info credentials");
        }
        if (uri.getRawFragment() != null) {
            throw new IOException("MINOS_POSTGRES_URL must not contain a fragment");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IOException("MINOS_POSTGRES_URL must contain a host");
        }
        String database = uri.getRawPath();
        if (database == null || database.isBlank() || "/".equals(database)) {
            throw new IOException("MINOS_POSTGRES_URL must contain a database name");
        }
        Map<String, String> query = queryParameters(uri.getRawQuery());
        for (String key : query.keySet()) {
            if (!ALLOWED_URL_PARAMETERS.contains(key)) {
                throw new IOException("MINOS_POSTGRES_URL contains unsupported parameter: " + key);
            }
        }
        String sslMode = query.get("sslmode");
        if (sslMode != null && !ALLOWED_SSL_MODES.contains(sslMode.toLowerCase(Locale.ROOT))) {
            throw new IOException("MINOS_POSTGRES_URL contains an unsupported sslmode");
        }

        if (managed) {
            if (!MANAGED_DOCKER_HOST.equalsIgnoreCase(host) && !loopbackHost(host)) {
                throw new IOException("managed PostgreSQL must use the MINOS Docker service or loopback");
            }
            return;
        }
        if (loopbackHost(host)) return;

        if (sslMode == null || !"verify-full".equalsIgnoreCase(sslMode)) {
            throw new IOException("external PostgreSQL requires sslmode=verify-full");
        }
    }

    private static Map<String, String> queryParameters(String rawQuery) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) return values;
        for (String pair : rawQuery.split("&", -1)) {
            if (pair.isEmpty()) {
                throw new IOException("MINOS_POSTGRES_URL contains an empty query parameter name");
            }
            int separator = pair.indexOf('=');
            String rawKey = separator < 0 ? pair : pair.substring(0, separator);
            String rawValue = separator < 0 ? "" : pair.substring(separator + 1);
            try {
                String key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                String decoded = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
                if (key.isEmpty()) {
                    throw new IOException("MINOS_POSTGRES_URL contains an empty query parameter name");
                }
                if (values.putIfAbsent(key, decoded) != null) {
                    throw new IOException("MINOS_POSTGRES_URL contains duplicate parameter: " + key);
                }
            } catch (IllegalArgumentException exception) {
                throw new IOException("MINOS_POSTGRES_URL contains invalid query encoding", exception);
            }
        }
        return values;
    }

    private static boolean loopbackHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(normalized)
                || normalized.startsWith("127.")
                || "::1".equals(normalized)
                || "0:0:0:0:0:0:0:1".equals(normalized);
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
        private final Connection connection;
        private boolean reusable = true;
        private int transactionDepth;
        private boolean rollbackOnly;

        private LeaseContext(Connection connection) {
            this.connection = connection;
        }
    }

    record PoolStats(int maximumSize, int leased, int idle, int physical, long acquisitionTimeouts) {
    }
}
