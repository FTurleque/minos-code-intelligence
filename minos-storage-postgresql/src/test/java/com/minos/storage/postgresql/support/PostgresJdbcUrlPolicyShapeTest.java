package com.minos.storage.postgresql.support;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Shape of the JDBC endpoint: every malformed or ambiguous URL is refused before the driver sees it, and the
 * refusal never echoes the value of a parameter (it could be a credential).
 */
class PostgresJdbcUrlPolicyShapeTest {
    private static final String LOOPBACK = "jdbc:postgresql://127.0.0.1:5432/minos";
    private static final String EXTERNAL = "jdbc:postgresql://db.example.com:5432/minos";

    @Test
    void aUrlThatIsNotAPostgresJdbcUrlIsRefused() {
        assertEquals("MINOS_POSTGRES_URL must use jdbc:postgresql://",
                refusal("postgresql://127.0.0.1:5432/minos", false));
        assertEquals("MINOS_POSTGRES_URL must use jdbc:postgresql://",
                refusal("jdbc:postgresql:minos", false));
    }

    @Test
    void aUrlThatIsNotAValidUriIsRefusedWithoutEchoingIt() {
        String message = refusal("jdbc:postgresql://127.0.0.1:5432/mi nos", false);

        assertEquals("MINOS_POSTGRES_URL is invalid", message);
    }

    @Test
    void userInfoCredentialsAreRefusedWithoutEchoingThem() {
        String message = refusal("jdbc:postgresql://minos:fixture-value@127.0.0.1:5432/minos", false);

        assertEquals("MINOS_POSTGRES_URL must not contain user-info credentials", message);
        assertFalse(message.contains("fixture-value"), message);
    }

    @Test
    void aFragmentIsRefused() {
        assertEquals("MINOS_POSTGRES_URL must not contain a fragment", refusal(LOOPBACK + "#fragment", false));
        assertEquals("MINOS_POSTGRES_URL must not contain a fragment",
                refusal(EXTERNAL + "?sslmode=verify-full#", false));
    }

    @Test
    void aMissingHostIsRefused() {
        assertEquals("MINOS_POSTGRES_URL must contain a host", refusal("jdbc:postgresql:///minos", false));
        assertEquals("MINOS_POSTGRES_URL must contain a host", refusal("jdbc:postgresql:///minos", true));
    }

    @Test
    void aMissingDatabaseNameIsRefused() {
        for (String url : new String[]{
                "jdbc:postgresql://127.0.0.1:5432",
                "jdbc:postgresql://127.0.0.1:5432/",
                "jdbc:postgresql://minos-postgres"
        }) {
            assertEquals("MINOS_POSTGRES_URL must contain a database name", refusal(url, true), url);
        }
    }

    @Test
    void anUnsupportedSslModeIsRefusedEvenOnLoopback() {
        assertEquals("MINOS_POSTGRES_URL contains an unsupported sslmode", refusal(LOOPBACK + "?sslmode=bogus", false));
        assertEquals("MINOS_POSTGRES_URL contains an unsupported sslmode", refusal(LOOPBACK + "?sslmode=", false));
        assertEquals("MINOS_POSTGRES_URL contains an unsupported sslmode", refusal(LOOPBACK + "?sslmode", true));
    }

    @Test
    void everyDocumentedSslModeIsAcceptedOnLoopback() {
        for (String mode : new String[]{"disable", "allow", "prefer", "require", "verify-ca", "verify-full"}) {
            assertDoesNotThrow(() -> PostgresJdbcUrlPolicy.validate(LOOPBACK + "?sslmode=" + mode, false), mode);
        }
    }

    @Test
    void anEmptyParameterNameIsRefused() {
        assertEquals("MINOS_POSTGRES_URL contains an empty query parameter name",
                refusal(LOOPBACK + "?=disable", false));
        assertEquals("MINOS_POSTGRES_URL contains an empty query parameter name",
                refusal(LOOPBACK + "?sslmode=disable&", false));
        assertEquals("MINOS_POSTGRES_URL contains an empty query parameter name",
                refusal(LOOPBACK + "?&sslmode=disable", false));
    }

    @Test
    void anUnsupportedParameterIsNamedButItsValueIsNeverEchoed() {
        String message = refusal(LOOPBACK + "?password=fixture-value", false);

        assertEquals("MINOS_POSTGRES_URL contains unsupported parameter: password", message);
        assertFalse(message.contains("fixture-value"), message);
    }

    @Test
    void aDuplicateParameterIsNamedInTheRefusal() {
        assertEquals("MINOS_POSTGRES_URL contains duplicate parameter: sslmode",
                refusal(LOOPBACK + "?sslmode=disable&sslmode=require", false));
    }

    @Test
    void anInvalidPercentEncodingIsRefusedAsAnInvalidUrlBeforeItsParametersAreRead() {
        assertEquals("MINOS_POSTGRES_URL is invalid", refusal(EXTERNAL + "?sslmode=verify-full%ZZ", false));
        assertEquals("MINOS_POSTGRES_URL is invalid", refusal(LOOPBACK + "?sslmode=disable%", false));
        assertEquals("MINOS_POSTGRES_URL is invalid", refusal(LOOPBACK + "?ssl%2mode=disable", true));
    }

    @Test
    void anEmptyQueryIsTheSameAsNoQuery() {
        assertDoesNotThrow(() -> PostgresJdbcUrlPolicy.validate(LOOPBACK + "?", false));
        assertEquals("external PostgreSQL requires sslmode=verify-full", refusal(EXTERNAL + "?", false));
    }

    @Test
    void managedStorageAcceptsOnlyTheDockerServiceNameOrLoopbackWhateverTheSslMode() {
        assertDoesNotThrow(() -> PostgresJdbcUrlPolicy.validate("jdbc:postgresql://MINOS-POSTGRES:5432/minos", true));
        assertDoesNotThrow(() -> PostgresJdbcUrlPolicy.validate("jdbc:postgresql://LOCALHOST/minos", true));
        assertEquals("managed PostgreSQL must use the MINOS Docker service or loopback",
                refusal(EXTERNAL + "?sslmode=verify-full", true));
        assertEquals("managed PostgreSQL must use the MINOS Docker service or loopback",
                refusal("jdbc:postgresql://minos-postgres.example.com:5432/minos", true));
    }

    @Test
    void theDockerServiceNameIsNotALoopbackForExternalStorage() {
        assertEquals("external PostgreSQL requires sslmode=verify-full",
                refusal("jdbc:postgresql://minos-postgres:5432/minos", false));
    }

    @Test
    void onlyAFullDottedQuadStartingWith127IsAnIpv4Loopback() {
        for (String host : new String[]{"127.0.0.1", "127.255.255.255", "127.000.000.001"}) {
            assertDoesNotThrow(
                    () -> PostgresJdbcUrlPolicy.validate("jdbc:postgresql://" + host + ":5432/minos", false), host);
        }
        for (String host : new String[]{
                "128.0.0.1", "126.255.255.255", "10.0.0.127", "0.0.0.0", "1.2.3.4",
                "127.0.0.256", "127.0.0", "127.0.0.1.1", "127..0.1", "127.0.0.1234", "127.0.0.x", "localhost.example.com"
        }) {
            assertThrows(IOException.class,
                    () -> PostgresJdbcUrlPolicy.validate("jdbc:postgresql://" + host + ":5432/minos", false), host);
            assertThrows(IOException.class,
                    () -> PostgresJdbcUrlPolicy.validate("jdbc:postgresql://" + host + ":5432/minos", true), host);
        }
    }

    private static String refusal(String url, boolean managed) {
        return assertThrows(IOException.class, () -> PostgresJdbcUrlPolicy.validate(url, managed), url).getMessage();
    }
}
