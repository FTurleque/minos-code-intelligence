package com.minos.storage.postgresql.support;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One physical connection of the query pool, the instant it was put back to rest, and the handle callers
 * receive instead of the driver connection. The handle forwards every call and records what the current lease
 * did, so the pool can tell whether a connection loss hit the very first statement of a call.
 *
 * <p>A connection is held by one thread at a time, but by different threads over its life: its bookkeeping
 * fields are volatile so that each holder reads what the previous one wrote.</p>
 */
final class PooledConnection {
    private static final Set<String> STATEMENT_FACTORIES = Set.of("createStatement", "prepareStatement", "prepareCall");
    /** Connection calls that neither send a statement nor change what a replay would observe. */
    private static final Set<String> NEUTRAL_CONNECTION_CALLS = Set.of(
            "isClosed", "getAutoCommit", "isValid", "getWarnings", "clearWarnings", "createArrayOf");
    private static final String EXECUTE_PREFIX = "execute";
    private static final String READ_EXECUTION = "executeQuery";

    private final Connection raw;
    private final Connection handle;
    // The borrower may hand the connection to another thread inside its call: the reference is atomic and
    // LeaseActivity guards its own state.
    private final AtomicReference<LeaseActivity> activity = new AtomicReference<>(new LeaseActivity());
    private volatile long idleSinceNanos;
    private volatile boolean leasedWithoutValidation;

    PooledConnection(Connection raw) {
        this.raw = raw;
        this.handle = (Connection) proxy(Connection.class, new Tracker(null));
    }

    /** The driver connection, for the pool's own housekeeping only. */
    Connection raw() {
        return raw;
    }

    /** Starts a lease: later calls on the handle are recorded in {@code leaseActivity}. */
    Connection lease(LeaseActivity leaseActivity) {
        activity.set(leaseActivity);
        return handle;
    }

    /** Records whether the lease being started takes this connection from rest without validating it. */
    void leasedWithoutValidation(boolean withoutValidation) {
        leasedWithoutValidation = withoutValidation;
    }

    /** Whether the current lease took this connection from rest without validating it. */
    boolean leasedWithoutValidation() {
        return leasedWithoutValidation;
    }

    void restingSince(long nanos) {
        idleSinceNanos = nanos;
    }

    /** Monotonic-clock difference, exact across a wrap of {@code System.nanoTime}. */
    long idleNanos(long nowNanos) {
        return nowNanos - idleSinceNanos;
    }

    private static Object proxy(Class<?> type, InvocationHandler handler) {
        return Proxy.newProxyInstance(PooledConnection.class.getClassLoader(), new Class<?>[]{type}, handler);
    }

    /** What one lease did with its connection, as far as a replay of the call is concerned. */
    static final class LeaseActivity {
        private int executionsStarted;
        private boolean replayForbidden;
        private boolean transactional;

        /** A pool-managed transaction is open: nothing the call writes is durable before its commit. */
        synchronized void transactional(boolean open) {
            transactional = open;
        }

        synchronized void forbidReplay() {
            replayForbidden = true;
        }

        private synchronized void executionStarted(boolean read) {
            executionsStarted++;
            // A write sent in autocommit may have been applied even though its acknowledgement was lost.
            if (!read && !transactional) replayForbidden = true;
        }

        /**
         * True while the call has completed no statement, has started at most one, and did nothing whose
         * effect the pool cannot account for.
         */
        synchronized boolean replayAllowed() {
            return !replayForbidden && executionsStarted <= 1;
        }
    }

    /** Forwards the calls made on the handle ({@code statement == null}) or on one of its statements. */
    private final class Tracker implements InvocationHandler {
        private final Object statement;

        private Tracker(Object statement) {
            this.statement = statement;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            try {
                if (method.getDeclaringClass() == Object.class) return identity(proxy, method.getName(), arguments);
                return statement == null ? onConnection(method, arguments) : onStatement(method, arguments);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }

        private Object onConnection(Method method, Object[] arguments) throws ReflectiveOperationException {
            String name = method.getName();
            if (STATEMENT_FACTORIES.contains(name)) {
                return proxy(method.getReturnType(), new Tracker(method.invoke(raw, arguments)));
            }
            if (!NEUTRAL_CONNECTION_CALLS.contains(name)) activity.get().forbidReplay();
            return method.invoke(raw, arguments);
        }

        private Object onStatement(Method method, Object[] arguments) throws ReflectiveOperationException {
            String name = method.getName();
            if ("getConnection".equals(name)) return handle;
            if (!name.startsWith(EXECUTE_PREFIX)) {
                // An unwrapped statement executes behind the handle: the lease can no longer be accounted for.
                if ("unwrap".equals(name)) activity.get().forbidReplay();
                return method.invoke(statement, arguments);
            }
            LeaseActivity current = activity.get();
            current.executionStarted(READ_EXECUTION.equals(name));
            Object result = method.invoke(statement, arguments);
            current.forbidReplay();
            return result;
        }

    }

    /** {@code equals}, {@code hashCode} and {@code toString}: identity, and no driver text. */
    private static Object identity(Object proxy, String name, Object[] arguments) {
        return switch (name) {
            case "equals" -> proxy == arguments[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> "pooled PostgreSQL JDBC handle";
        };
    }
}
