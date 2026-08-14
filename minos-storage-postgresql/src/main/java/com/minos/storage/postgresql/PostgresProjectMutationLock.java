package com.minos.storage.postgresql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/** Shared project mutation lock used by transactional writes and lifecycle session leases. */
final class PostgresProjectMutationLock {
    private static final ThreadLocal<UUID> LIFECYCLE_PROJECT = new ThreadLocal<>();

    private PostgresProjectMutationLock() {
    }

    static long key(UUID projectId) {
        UUID value = Objects.requireNonNull(projectId, "projectId");
        return value.getMostSignificantBits() ^ value.getLeastSignificantBits();
    }

    static void acquire(Connection connection, UUID projectId) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        UUID id = Objects.requireNonNull(projectId, "projectId");
        if (lifecycleOwned(id)) return;
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
            statement.setLong(1, key(id));
            statement.execute();
        }
    }

    static void enterLifecycle(UUID projectId) {
        UUID id = Objects.requireNonNull(projectId, "projectId");
        UUID current = LIFECYCLE_PROJECT.get();
        if (current != null && !current.equals(id)) {
            throw new IllegalStateException(
                    "thread already owns a PostgreSQL lifecycle lock for another project: " + current);
        }
        LIFECYCLE_PROJECT.set(id);
    }

    static void exitLifecycle(UUID projectId) {
        UUID id = Objects.requireNonNull(projectId, "projectId");
        UUID current = LIFECYCLE_PROJECT.get();
        if (!id.equals(current)) {
            throw new IllegalStateException(
                    "PostgreSQL lifecycle ownership context mismatch: expected=" + id + " actual=" + current);
        }
        LIFECYCLE_PROJECT.remove();
    }

    static boolean lifecycleOwned(UUID projectId) {
        return Objects.requireNonNull(projectId, "projectId").equals(LIFECYCLE_PROJECT.get());
    }
}
