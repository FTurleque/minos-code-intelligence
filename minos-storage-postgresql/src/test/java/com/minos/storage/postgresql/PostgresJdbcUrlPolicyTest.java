package com.minos.storage.postgresql;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Politique d'URL PostgreSQL. MINOS-AUD-B07 : le pilote pgjdbc ne reconnaît que la clé exacte {@code sslmode} ;
 * {@code SSLMODE=verify-full} et {@code ssl%6Dode=verify-full} sont ignorés et la connexion retombe sur le mode par
 * défaut, sans vérification de certificat. La politique compare donc le nom du paramètre brut, comme le pilote.
 */
class PostgresJdbcUrlPolicyTest {
    private static final String EXTERNAL = "jdbc:postgresql://db.example.com:5432/minos";

    @Test
    void anExternalUrlWithAnUpperCaseSslModeKeyIsRefusedBecauseTheDriverWouldIgnoreIt() {
        assertThrows(IOException.class, () -> PostgresJdbcUrlPolicy.validate(EXTERNAL + "?SSLMODE=verify-full", false));
    }

    @Test
    void anExternalUrlWithAPercentEncodedSslModeKeyIsRefusedBecauseTheDriverWouldIgnoreIt() {
        assertThrows(IOException.class, () -> PostgresJdbcUrlPolicy.validate(EXTERNAL + "?ssl%6Dode=verify-full", false));
    }

    @Test
    void theExactKeyIsAccepted() {
        assertDoesNotThrow(() -> PostgresJdbcUrlPolicy.validate(EXTERNAL + "?sslmode=verify-full", false));
    }

    @Test
    void theValueIsReadLikeTheDriverReadsIt() {
        assertDoesNotThrow(() -> PostgresJdbcUrlPolicy.validate(EXTERNAL + "?sslmode=VERIFY-FULL", false));
        assertDoesNotThrow(() -> PostgresJdbcUrlPolicy.validate(EXTERNAL + "?sslmode=verify%2Dfull", false));
    }

    @Test
    void anExternalHostWithoutAModeOrWithAWeakerOneIsRefusedWithTheDocumentedMessage() {
        for (String url : new String[]{EXTERNAL, EXTERNAL + "?sslmode=require", EXTERNAL + "?sslmode=prefer"}) {
            IOException refusal = assertThrows(IOException.class, () -> PostgresJdbcUrlPolicy.validate(url, false), url);
            assertTrue(refusal.getMessage().contains("external PostgreSQL requires sslmode=verify-full"),
                    refusal.getMessage());
        }
    }

    @Test
    void aDuplicateSslModeIsRefusedWhateverItsCase() {
        assertThrows(IOException.class,
                () -> PostgresJdbcUrlPolicy.validate(EXTERNAL + "?sslmode=verify-full&sslmode=verify-full", false));
        assertThrows(IOException.class,
                () -> PostgresJdbcUrlPolicy.validate(EXTERNAL + "?sslmode=verify-full&SSLMODE=verify-full", false));
    }

    @Test
    void aLoopbackHostWithAnUnrecognizedParameterNameIsRefusedBecauseTheDriverWouldIgnoreIt() {
        assertThrows(IOException.class,
                () -> PostgresJdbcUrlPolicy.validate("jdbc:postgresql://127.0.0.1:5432/minos?SSLMODE=disable", false));
        assertThrows(IOException.class,
                () -> PostgresJdbcUrlPolicy.validate("jdbc:postgresql://localhost:5432/minos?SSLMODE=disable", true));
    }

    /** MINOS-AUD-B13 : le JDK renvoie l'hôte IPv6 entre crochets ; la comparaison attendait l'hôte nu. */
    @Test
    void theIpv6LoopbackNeedsNoModeForExternalAndManagedStorage() {
        for (String host : new String[]{"[::1]", "[0:0:0:0:0:0:0:1]"}) {
            String url = "jdbc:postgresql://" + host + ":5432/minos";

            assertDoesNotThrow(() -> PostgresJdbcUrlPolicy.validate(url, false), url);
            assertDoesNotThrow(() -> PostgresJdbcUrlPolicy.validate(url, true), url);
        }
    }

    @Test
    void anotherIpv6AddressStillRequiresVerifyFull() {
        String url = "jdbc:postgresql://[2001:db8::1]:5432/minos";

        assertThrows(IOException.class, () -> PostgresJdbcUrlPolicy.validate(url, false));
        assertThrows(IOException.class, () -> PostgresJdbcUrlPolicy.validate(url + "?sslmode=require", false));
        assertDoesNotThrow(() -> PostgresJdbcUrlPolicy.validate(url + "?sslmode=verify-full", false));
    }

    @Test
    void anIpv6FormThatIsNotListedStaysExternalAndFailsClosed() {
        for (String host : new String[]{"[::ffff:127.0.0.1]", "[0::1]", "[::2]"}) {
            String url = "jdbc:postgresql://" + host + ":5432/minos";

            assertThrows(IOException.class, () -> PostgresJdbcUrlPolicy.validate(url, false), url);
            assertThrows(IOException.class, () -> PostgresJdbcUrlPolicy.validate(url, true), url);
        }
    }

    @Test
    void aLoopbackHostStillNeedsNoModeAndAnUnknownParameterIsStillRefused() {
        assertDoesNotThrow(() -> PostgresJdbcUrlPolicy.validate("jdbc:postgresql://127.0.0.1:5432/minos", false));
        assertThrows(IOException.class,
                () -> PostgresJdbcUrlPolicy.validate("jdbc:postgresql://127.0.0.1:5432/minos?user=attacker", false));
    }
}
