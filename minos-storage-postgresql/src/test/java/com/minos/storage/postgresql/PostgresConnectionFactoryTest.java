package com.minos.storage.postgresql;

import com.minos.io.CommitUncertainException;
import com.minos.storage.StorageBackendConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresConnectionFactoryTest {

    private static final String VALID_URL = "jdbc:postgresql://localhost:5432/minos_test";

    @Test
    void rejectsEmptyUrl(@TempDir Path home) {
        assertThrows(IOException.class,
                () -> factory(home, "", "minos", "secret", "minos"));
    }

    @Test
    void rejectsNonPostgresUrl(@TempDir Path home) {
        assertThrows(IOException.class,
                () -> factory(home, "jdbc:h2:mem:test", "minos", "secret", "minos"));
    }

    @Test
    void rejectsEmptyUser(@TempDir Path home) {
        assertThrows(IOException.class,
                () -> factory(home, VALID_URL, "", "secret", "minos"));
    }

    @Test
    void rejectsEmptyPassword(@TempDir Path home) {
        assertThrows(IOException.class,
                () -> factory(home, VALID_URL, "minos", "", "minos"));
    }

    @Test
    void rejectsBlankPassword(@TempDir Path home) {
        assertThrows(IOException.class,
                () -> factory(home, VALID_URL, "minos", "   ", "minos"));
    }

    @Test
    void externalPostgresRequiresVerifyFull(@TempDir Path home) {
        assertThrows(IOException.class, () -> factory(
                home,
                "jdbc:postgresql://db.example.com:5432/minos",
                "minos", "secret", "minos"));

        assertDoesNotThrow(() -> factory(
                home,
                "jdbc:postgresql://db.example.com:5432/minos?sslmode=verify-full",
                "minos", "secret", "minos").close());
    }

    @Test
    void rejectsCredentialsAndSecretsInsideJdbcUrl(@TempDir Path home) {
        assertThrows(IOException.class, () -> factory(
                home,
                "jdbc:postgresql://minos:secret@db.example.com:5432/minos?sslmode=verify-full",
                "minos", "secret", "minos"));
        assertThrows(IOException.class, () -> factory(
                home,
                "jdbc:postgresql://db.example.com:5432/minos?sslmode=verify-full&password=secret",
                "minos", "secret", "minos"));
    }

    @Test
    void externalJdbcUrlUsesAStrictAllowlistForDriverParameters(@TempDir Path home) {
        String prefix = "jdbc:postgresql://db.example.com:5432/minos?sslmode=verify-full&";
        for (String parameter : new String[]{
                "user=attacker", "password=secret", "currentSchema=public", "options=-c%20search_path%3Dpublic",
                "connectTimeout=0", "socketTimeout=0", "ApplicationName=spoofed",
                "sslfactory=example.CustomFactory", "sslfactoryarg=value",
                "sslhostnameverifier=example.CustomVerifier"
        }) {
            assertThrows(IOException.class,
                    () -> factory(home, prefix + parameter, "minos", "secret", "minos"),
                    parameter);
        }
    }

    @Test
    void rejectsDuplicateCaseVariantsAndInvalidQueryEncoding(@TempDir Path home) {
        assertThrows(IOException.class, () -> factory(
                home,
                "jdbc:postgresql://db.example.com:5432/minos?sslmode=verify-full&SSLMODE=verify-full",
                "minos", "secret", "minos"));
        assertThrows(IOException.class, () -> factory(
                home,
                "jdbc:postgresql://db.example.com:5432/minos?sslmode=verify-full%ZZ",
                "minos", "secret", "minos"));
        assertThrows(IOException.class, () -> factory(
                home,
                "jdbc:postgresql://db.example.com:5432/minos?sslmode=verify-full&&",
                "minos", "secret", "minos"));
        assertThrows(IOException.class, () -> factory(
                home,
                "jdbc:postgresql://db.example.com:5432/minos?sslmode=%20verify-full%20",
                "minos", "secret", "minos"));
    }

    @Test
    void loopbackRemainsSupportedButCannotOverrideOwnedJdbcProperties(@TempDir Path home) {
        assertDoesNotThrow(() -> factory(
                home,
                "jdbc:postgresql://127.0.0.1:5432/minos?sslmode=disable",
                "minos", "secret", "minos").close());
        assertThrows(IOException.class, () -> factory(
                home,
                "jdbc:postgresql://localhost:5432/minos?currentSchema=public",
                "minos", "secret", "minos"));
    }

    @Test
    void managedPostgresAllowsOnlyDockerServiceOrLoopback(@TempDir Path home) {
        StorageBackendConfiguration managed = new StorageBackendConfiguration(
                "postgresql", home, "jdbc:postgresql://minos-postgres:5432/minos",
                "minos", "secret", "minos", true);
        assertDoesNotThrow(() -> new PostgresConnectionFactory(managed).close());

        StorageBackendConfiguration unsafeManaged = new StorageBackendConfiguration(
                "postgresql", home, "jdbc:postgresql://db.example.com:5432/minos",
                "minos", "secret", "minos", true);
        assertThrows(IOException.class, () -> new PostgresConnectionFactory(unsafeManaged));
    }

    @Test
    void sqlStateClass08IsTreatedAsConnectionFailure() {
        assertTrue(PostgresConnectionFactory.isConnectionFailure(
                new SQLException("connection lost", "08006")));
        assertFalse(PostgresConnectionFactory.isConnectionFailure(
                new SQLException("constraint", "23505")));
    }

    @Test
    void connectionLossWhileAcknowledgingCommitIsExplicitlyUncertain() {
        Connection connection = commitFailingConnection(new SQLException("connection lost after COMMIT", "08006"));

        assertThrows(CommitUncertainException.class,
                () -> PostgresConnectionFactory.commitTransaction(connection));
    }

    @Test
    void nonConnectionCommitFailureRemainsDefinitiveSqlFailure() {
        Connection connection = commitFailingConnection(new SQLException("constraint", "23505"));

        assertThrows(SQLException.class,
                () -> PostgresConnectionFactory.commitTransaction(connection));
    }

    private static Connection commitFailingConnection(SQLException failure) {
        return (Connection) Proxy.newProxyInstance(
                PostgresConnectionFactoryTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    if ("commit".equals(method.getName())) throw failure;
                    if ("toString".equals(method.getName())) return "commit-failing-connection";
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == byte.class) return (byte) 0;
                    if (type == short.class) return (short) 0;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == float.class) return 0F;
                    if (type == double.class) return 0D;
                    if (type == char.class) return '\0';
                    return null;
                });
    }

    private static PostgresConnectionFactory factory(
            Path home, String url, String user, String password, String schema) throws IOException {
        return new PostgresConnectionFactory(
                new StorageBackendConfiguration("postgresql", home, url, user, password, schema));
    }
}
