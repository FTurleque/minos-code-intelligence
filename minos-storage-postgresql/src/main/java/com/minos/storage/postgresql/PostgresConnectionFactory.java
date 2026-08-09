package com.minos.storage.postgresql;

import com.minos.storage.StorageBackendConfiguration;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Properties;

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
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("currentSchema", schema + ",public");
        return DriverManager.getConnection(url, properties);
    }

    String schema() { return schema; }

    private static String require(String value, String name) throws IOException {
        if (value == null || value.isBlank()) throw new IOException("missing required PostgreSQL setting: " + name);
        return value.trim();
    }
}
