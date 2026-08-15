package com.minos.storage.postgresql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/** Shared project mutation lock used by transactional writes and lifecycle session leases. */
final class PostgresProjectMutationLock {
    private static final String PROJECT_ID_LABEL = "projectId";
    private static final String CONNECTION_LABEL = "connection";
    private static final ThreadLocal<LifecycleOwnership> LIFECYCLE = new ThreadLocal<>();

    private PostgresProjectMutationLock() {
    }

    static long key(UUID projectId) {
        UUID value = Objects.requireNonNull(projectId, PROJECT_ID_LABEL);
        return value.getMostSignificantBits() ^ value.getLeastSignificantBits();
    }

    static void acquire(Connection connection, UUID projectId) throws SQLException {
        Connection currentConnection = Objects.requireNonNull(connection, CONNECTION_LABEL);
        UUID id = Objects.requireNonNull(projectId, PROJECT_ID_LABEL);
        if (lifecycleOwned(id, currentConnection)) return;
        try (PreparedStatement statement = currentConnection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
            statement.setLong(1, key(id));
            statement.execute();
        }
    }

    static void enterLifecycle(UUID projectId, Connection connection) {
        UUID id = Objects.requireNonNull(projectId, PROJECT_ID_LABEL);
        Connection sessionConnection = Objects.requireNonNull(connection, CONNECTION_LABEL);
        LifecycleOwnership current = LIFECYCLE.get();
        if (current != null
                && (!current.projectId().equals(id) || current.connection() != sessionConnection)) {
            throw new IllegalStateException(
                    "thread already owns a PostgreSQL lifecycle lock for another project or connection: "
                            + current.projectId());
        }
        LIFECYCLE.set(new LifecycleOwnership(id, sessionConnection));
    }

    static void exitLifecycle(UUID projectId, Connection connection) {
        UUID id = Objects.requireNonNull(projectId, PROJECT_ID_LABEL);
        Connection sessionConnection = Objects.requireNonNull(connection, CONNECTION_LABEL);
        LifecycleOwnership current = LIFECYCLE.get();
        if (current == null || !current.projectId().equals(id) || current.connection() != sessionConnection) {
            throw new IllegalStateException(
                    "PostgreSQL lifecycle ownership context mismatch: expected=" + id
                            + " actual=" + (current == null ? null : current.projectId()));
        }
        LIFECYCLE.remove();
    }

    static boolean lifecycleOwned(UUID projectId, Connection connection) {
        LifecycleOwnership current = LIFECYCLE.get();
        return current != null
                && Objects.requireNonNull(projectId, PROJECT_ID_LABEL).equals(current.projectId())
                && Objects.requireNonNull(connection, CONNECTION_LABEL) == current.connection();
    }

    private record LifecycleOwnership(UUID projectId, Connection connection) {
        private LifecycleOwnership {
            Objects.requireNonNull(projectId, PROJECT_ID_LABEL);
            Objects.requireNonNull(connection, CONNECTION_LABEL);
        }
    }
}
