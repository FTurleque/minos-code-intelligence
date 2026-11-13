package com.minos.storage.postgresql.support;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Connection parameters of the PostgreSQL server the integration tests use. The server is started outside the test
 * JVM (by {@code scripts/dev/start-test-postgres.sh} or {@code .ps1}, locally and in CI): no test starts a container
 * or talks to a Docker daemon.
 *
 * <p>Each parameter is read from a system property, then from an environment variable. The CI passes the password
 * through the environment only, so it never appears on a command line; PIT minion JVMs inherit the environment of
 * Maven but not its user properties, which is why the URL and the user have the same fallback.</p>
 *
 * @param adminUrl JDBC URL of a database the user can connect to in order to create the per-class databases
 * @param user     user with the {@code CREATEDB} privilege
 * @param password password of {@code user}; never part of {@link #toString()}
 */
public record PostgresTestServer(String adminUrl, String user, String password) {

    public static final String URL_PROPERTY = "minos.postgresql.test.url";
    public static final String USER_PROPERTY = "minos.postgresql.test.user";
    public static final String PASSWORD_PROPERTY = "minos.postgresql.test.password";
    public static final String URL_VARIABLE = "MINOS_TEST_POSTGRES_URL";
    public static final String USER_VARIABLE = "MINOS_TEST_POSTGRES_USER";
    public static final String PASSWORD_VARIABLE = "MINOS_TEST_POSTGRES_PASSWORD";

    public PostgresTestServer {
        Objects.requireNonNull(adminUrl, "adminUrl");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(password, "password");
    }

    /** Thrown when a parameter is missing; the message names the property and the variable, never a value. */
    public static final class MissingParameterException extends Exception {
        private static final long serialVersionUID = 1L;

        MissingParameterException(String message) {
            super(message);
        }
    }

    /** The parameters of this JVM: system properties first, then environment variables. */
    public static PostgresTestServer fromEnvironment() throws MissingParameterException {
        return resolve(System::getProperty, System::getenv);
    }

    /** The parameters read from {@code properties}, then {@code environment}; both return null for an absent name. */
    public static PostgresTestServer resolve(UnaryOperator<String> properties, UnaryOperator<String> environment)
            throws MissingParameterException {
        return new PostgresTestServer(
                parameter(properties, environment, URL_PROPERTY, URL_VARIABLE),
                parameter(properties, environment, USER_PROPERTY, USER_VARIABLE),
                parameter(properties, environment, PASSWORD_PROPERTY, PASSWORD_VARIABLE));
    }

    /** The password of this JVM's test server, for test pools that build their own configuration. */
    public static String currentPassword() {
        try {
            return fromEnvironment().password();
        } catch (MissingParameterException missing) {
            throw new IllegalStateException(missing.getMessage(), missing);
        }
    }

    /** JDBC URL of {@code database} on the same server, keeping the query parameters of the administration URL. */
    public String urlForDatabase(String database) {
        int queryStart = adminUrl.indexOf('?');
        String base = queryStart < 0 ? adminUrl : adminUrl.substring(0, queryStart);
        String query = queryStart < 0 ? "" : adminUrl.substring(queryStart);
        return base.substring(0, base.lastIndexOf('/') + 1) + database + query;
    }

    /** The administration URL without its query part, which may carry credentials: safe for a message. */
    public String displayUrl() {
        int queryStart = adminUrl.indexOf('?');
        return queryStart < 0 ? adminUrl : adminUrl.substring(0, queryStart);
    }

    @Override
    public String toString() {
        return "PostgresTestServer[" + displayUrl() + ", user=" + user + "]";
    }

    private static String parameter(UnaryOperator<String> properties, UnaryOperator<String> environment,
                                    String property, String variable) throws MissingParameterException {
        String value = properties.apply(property);
        if (value == null || value.isBlank()) {
            value = environment.apply(variable);
        }
        if (value == null || value.isBlank()) {
            throw new MissingParameterException("the PostgreSQL test server is not configured: neither the system "
                    + "property " + property + " nor the environment variable " + variable + " is set "
                    + "(start one with scripts/dev/start-test-postgres.sh or .ps1)");
        }
        return value;
    }
}
