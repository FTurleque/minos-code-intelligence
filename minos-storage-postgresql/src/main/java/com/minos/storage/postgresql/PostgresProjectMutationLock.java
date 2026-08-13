package com.minos.storage.postgresql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/** Shared transaction-scoped advisory lock for every durable mutation of one project. */
final class PostgresProjectMutationLock {
    private PostgresProjectMutationLock() {
    }

    static long key(UUID projectId) {
        UUID value = Objects.requireNonNull(projectId, "projectId");
        return value.getMostSignificantBits() ^ value.getLeastSignificantBits();
    }

    static void acquire(Connection connection, UUID projectId) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
            statement.setLong(1, key(projectId));
            statement.execute();
        }
    }
}
