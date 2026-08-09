package com.minos.storage.postgresql;

import com.minos.storage.StorageBackendConfiguration;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;

final class PostgresConnectionFactory {
    private final String url;
    private final String user;
    private final String password;
    private final String schema;

    PostgresConnectionFactory(StorageBackendConfiguration configuration) throws IOException {
        Objects.requireNonNull(configuration, "configuration");
        if (!configuration.postgresql()) throw new IOException("PostgreSQL configuration required");
        this.url = require(configuration.postgresUrl(), "MINOS_POSTGRES_URL");
        this.user = require(configuration.postgresUser(), "MINOS_POSTGRES_USER");
        this.password = require(configuration.postgresPassword(), "MINOS_POSTGRES_PASSWORD");
        this.schema = configuration.postgresSchema();
        if (!url.startsWith("jdbc:postgresql://")) {
            throw new IOException("MINOS_POSTGRES_URL must use jdbc:postgresql://");
        }
    }

    Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(url, user, password);
        try {
            // search_path is a PostgreSQL setting value, not executable SQL. Binding it as data keeps
            // both the configured MINOS schema and public (where pgvector is installed) without
            // concatenating configuration into a statement.
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT set_config('search_path', ?, false)")) {
                statement.setString(1, schema + ",public");
                statement.executeQuery().close();
            }
            return connection;
        } catch (SQLException | RuntimeException exception) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
    }

    String schema() { return schema; }

    private static String require(String value, String name) throws IOException {
        if (value == null || value.isBlank()) throw new IOException("missing required PostgreSQL setting: " + name);
        return value.trim();
    }
}
