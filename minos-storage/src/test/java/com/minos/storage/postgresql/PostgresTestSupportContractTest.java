package com.minos.storage.postgresql;

import com.minos.storage.postgresql.support.PostgresTestServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract of the PostgreSQL test harness without any server (AUD-DEP-14): a missing or unreachable server fails
 * in required mode and is skipped, with its reason, otherwise. No message carries the password.
 */
class PostgresTestSupportContractTest {

    private static final String PASSWORD = "contract-password-6f1d0c";
    private static final UnaryOperator<String> NOTHING = name -> null;

    @Test
    void missingUrlIsSkippedWithAReasonThatNamesTheProperty() {
        Optional<String> skip = PostgresTestSupport.prepareServer(NOTHING, NOTHING, false);

        assertTrue(skip.isPresent(), "an unconfigured server must skip the class");
        assertTrue(skip.get().contains(PostgresTestServer.URL_PROPERTY), skip.get());
    }

    @Test
    void missingUrlFailsInRequiredModeNamingTheProperty() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> PostgresTestSupport.prepareServer(NOTHING, NOTHING, true));

        assertTrue(failure.getMessage().startsWith("PostgreSQL integration tests are required but"), failure.getMessage());
        assertTrue(failure.getMessage().contains(PostgresTestServer.URL_PROPERTY), failure.getMessage());
    }

    @Test
    void unreachableServerFailsInRequiredModeWithoutThePassword() throws IOException {
        UnaryOperator<String> properties = properties("jdbc:postgresql://127.0.0.1:" + closedPort() + "/postgres");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> PostgresTestSupport.prepareServer(properties, NOTHING, true));

        assertTrue(failure.getMessage().contains("unreachable"), failure.getMessage());
        assertFalse(describe(failure).contains(PASSWORD), "the password leaks into the failure");
    }

    @Test
    void unreachableServerIsSkippedOutsideRequiredMode() throws IOException {
        UnaryOperator<String> properties = properties("jdbc:postgresql://127.0.0.1:" + closedPort() + "/postgres");

        Optional<String> skip = PostgresTestSupport.prepareServer(properties, NOTHING, false);

        assertTrue(skip.isPresent());
        assertTrue(skip.get().contains("unreachable"), skip.get());
        assertFalse(skip.get().contains(PASSWORD));
    }

    @Test
    void propertiesWinOverTheEnvironmentAndThePasswordFallsBackToIt() throws Exception {
        UnaryOperator<String> environment = Map.of(
                PostgresTestServer.URL_VARIABLE, "jdbc:postgresql://127.0.0.1:1/ignored",
                PostgresTestServer.USER_VARIABLE, "ignored",
                PostgresTestServer.PASSWORD_VARIABLE, PASSWORD)::get;
        UnaryOperator<String> properties = Map.of(
                PostgresTestServer.URL_PROPERTY, "jdbc:postgresql://127.0.0.1:55432/postgres?sslmode=disable",
                PostgresTestServer.USER_PROPERTY, "minos")::get;

        PostgresTestServer server = PostgresTestServer.resolve(properties, environment);

        assertEquals("jdbc:postgresql://127.0.0.1:55432/postgres?sslmode=disable", server.adminUrl());
        assertEquals("minos", server.user());
        assertEquals(PASSWORD, server.password());
        assertEquals("jdbc:postgresql://127.0.0.1:55432/minos_t_0123?sslmode=disable",
                server.urlForDatabase("minos_t_0123"));
        assertFalse(server.toString().contains(PASSWORD));
        assertFalse(server.toString().contains("sslmode"));
    }

    @Test
    void missingPasswordNamesBothSources() {
        UnaryOperator<String> properties = Map.of(
                PostgresTestServer.URL_PROPERTY, "jdbc:postgresql://127.0.0.1:55432/postgres",
                PostgresTestServer.USER_PROPERTY, "minos")::get;

        PostgresTestServer.MissingParameterException missing = assertThrows(
                PostgresTestServer.MissingParameterException.class,
                () -> PostgresTestServer.resolve(properties, NOTHING));

        assertTrue(missing.getMessage().contains(PostgresTestServer.PASSWORD_PROPERTY), missing.getMessage());
        assertTrue(missing.getMessage().contains(PostgresTestServer.PASSWORD_VARIABLE), missing.getMessage());
    }

    @Test
    void databaseNamesAreUniqueAndPrefixed() {
        String first = PostgresTestSupport.newDatabaseName();
        String second = PostgresTestSupport.newDatabaseName();

        assertTrue(first.matches("minos_t_[0-9a-f]{12}"), first);
        assertNotEquals(first, second);
    }

    private static UnaryOperator<String> properties(String url) {
        return Map.of(
                PostgresTestServer.URL_PROPERTY, url,
                PostgresTestServer.USER_PROPERTY, "minos",
                PostgresTestServer.PASSWORD_PROPERTY, PASSWORD)::get;
    }

    /** A loopback port nothing listens on: bound, then released. */
    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static String describe(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            text.append(current).append('\n');
        }
        return text.toString();
    }
}
