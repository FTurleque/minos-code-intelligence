package com.minos.storage.postgresql.support;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory stand-in for a PostgreSQL server: it hands the pool spy connections that count their validation
 * round trips and record every statement they execute, so pool behaviour is tested without a server.
 */
final class SpyDatabase implements PostgresConnectionFactory.ConnectionSource {
    static final String CONNECTION_LOST = "08006";

    private final List<Session> sessions = new CopyOnWriteArrayList<>();
    private final List<String> executed = new CopyOnWriteArrayList<>();
    private volatile SQLException refusal;

    @Override
    public Connection connect(String url, Properties properties) throws SQLException {
        SQLException refused = refusal;
        if (refused != null) throw refused;
        Session session = new Session(sessions.size() + 1);
        sessions.add(session);
        return session.connection;
    }

    /** Every later connection attempt fails with {@code failure}; {@code null} accepts connections again. */
    void refuseConnections(SQLException failure) {
        refusal = failure;
    }

    /** The session opened in {@code ordinal}-th position, counting from 1. */
    Session session(int ordinal) {
        return sessions.get(ordinal - 1);
    }

    int opened() {
        return sessions.size();
    }

    /** Statements that reached the server, as {@code "<session ordinal>:<sql>"}, in execution order. */
    List<String> executed() {
        return List.copyOf(executed);
    }

    int validations() {
        return sessions.stream().mapToInt(session -> session.validations.get()).sum();
    }

    /** One server session and the switches a test flips to make it misbehave. */
    final class Session {
        final int ordinal;
        final Connection connection;
        final AtomicInteger validations = new AtomicInteger();
        final AtomicInteger rollbacks = new AtomicInteger();
        final AtomicInteger commits = new AtomicInteger();
        volatile boolean closed;
        volatile boolean autoCommit = true;
        /** The server side is gone: validation answers false and every statement fails with a class 08 state. */
        volatile boolean broken;
        /** The next statement fails with this ordinary (non connection) error, once. */
        volatile SQLException nextStatementFailure;
        volatile RuntimeException validationFailure;
        volatile RuntimeException autoCommitFailure;
        volatile SQLException clearWarningsFailure;
        volatile SQLException closeFailure;
        /** Runs while the pool resets the connection before putting it back to rest. */
        volatile Runnable duringClearWarnings;

        private Session(int ordinal) {
            this.ordinal = ordinal;
            this.connection = proxy(Connection.class, this::onConnection);
        }

        private Object onConnection(Object proxy, Method method, Object[] arguments) throws SQLException {
            switch (method.getName()) {
                case "isValid":
                    validations.incrementAndGet();
                    if (validationFailure != null) throw validationFailure;
                    return !closed && !broken;
                case "isClosed":
                    return closed;
                case "close":
                    closed = true;
                    if (closeFailure != null) throw closeFailure;
                    return null;
                case "getAutoCommit":
                    if (autoCommitFailure != null) throw autoCommitFailure;
                    return autoCommit;
                case "setAutoCommit":
                    autoCommit = (Boolean) arguments[0];
                    return null;
                case "commit":
                    commits.incrementAndGet();
                    if (broken) throw new SQLException("connection lost", CONNECTION_LOST);
                    return null;
                case "rollback":
                    rollbacks.incrementAndGet();
                    if (broken) throw new SQLException("connection lost", CONNECTION_LOST);
                    return null;
                case "clearWarnings":
                    Runnable hook = duringClearWarnings;
                    if (hook != null) hook.run();
                    if (clearWarningsFailure != null) throw clearWarningsFailure;
                    return null;
                case "createStatement", "prepareStatement", "prepareCall":
                    if (closed) throw new SQLException("connection closed", "08003");
                    String sql = arguments == null ? null : (String) arguments[0];
                    return proxy(method.getReturnType(), (statement, call, values) -> onStatement(statement, sql, call, values));
                case "unwrap":
                    return proxy;
                case "getHoldability":
                    return 1;
                default:
                    return identity(proxy, method, arguments);
            }
        }

        private Object onStatement(Object proxy, String preparedSql, Method method, Object[] arguments) throws SQLException {
            String name = method.getName();
            if (name.startsWith("execute")) {
                String sql = arguments == null ? preparedSql : (String) arguments[0];
                if (broken || closed) throw new SQLException("connection lost", CONNECTION_LOST);
                SQLException failure = nextStatementFailure;
                if (failure != null) {
                    nextStatementFailure = null;
                    throw failure;
                }
                executed.add(ordinal + ":" + sql);
                return defaultValue(method.getReturnType());
            }
            if ("getConnection".equals(name)) return connection;
            if ("unwrap".equals(name)) return proxy;
            return identity(proxy, method, arguments);
        }
    }

    private static Object identity(Object proxy, Method method, Object[] arguments) {
        return switch (method.getName()) {
            case "equals" -> proxy == arguments[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "spy";
            default -> defaultValue(method.getReturnType());
        };
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(SpyDatabase.class.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
