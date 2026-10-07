package com.minos.storage.postgresql;

import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.postgresql.jdbc.SslMode;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-B07, garde contre la récidive : pour tout URL que la politique accepte, le mode TLS que le vrai pilote
 * pgjdbc retient est celui que la politique a validé. Une différence d'analyse entre les deux est le défaut lui-même.
 * Ce test casse si une future version du pilote change son analyse : la politique doit alors être relue.
 */
class PostgresJdbcUrlPolicyDriverParityTest {
    private static final String EXTERNAL = "jdbc:postgresql://db.example.com:5432/minos";

    private static final List<String> EXTERNAL_URLS = List.of(
            EXTERNAL + "?sslmode=verify-full",
            EXTERNAL + "?sslmode=VERIFY-FULL",
            EXTERNAL + "?sslmode=verify%2Dfull",
            EXTERNAL + "?SSLMODE=verify-full",
            EXTERNAL + "?SslMode=verify-full",
            EXTERNAL + "?ssl%6Dode=verify-full",
            EXTERNAL + "?ssl%6dode=verify-full",
            EXTERNAL + "?sslmode=verify-full&sslmode=verify-full",
            EXTERNAL + "?sslmode=verify-full&SSLMODE=disable",
            EXTERNAL + "?sslmode=verify-full&user=attacker",
            EXTERNAL + "?sslmode=require",
            EXTERNAL + "?sslmode=prefer",
            EXTERNAL + "?sslmode=disable",
            EXTERNAL);

    private static final List<String> LOOPBACK_URLS = List.of(
            "jdbc:postgresql://127.0.0.1:5432/minos",
            "jdbc:postgresql://localhost:5432/minos",
            "jdbc:postgresql://127.0.0.1:5432/minos?sslmode=disable",
            "jdbc:postgresql://127.0.0.1:5432/minos?sslmode=verify-full",
            "jdbc:postgresql://127.0.0.1:5432/minos?SSLMODE=disable",
            "jdbc:postgresql://127.0.0.1:5432/minos?ssl%6Dode=disable");

    @Test
    void everyExternalUrlTheMinosPolicyAcceptsIsReadByTheDriverAsVerifyFull() throws Exception {
        int accepted = 0;
        for (String url : EXTERNAL_URLS) {
            if (!accepts(url)) continue;
            accepted++;
            assertEquals(SslMode.VERIFY_FULL, driverMode(url), "the driver reads a different TLS mode for " + url);
        }
        assertTrue(accepted >= 3, "the sample must contain accepted URLs, or the test proves nothing: " + accepted);
    }

    @Test
    void everyUrlWhoseSslModeKeyTheDriverIgnoresIsRefusedByThePolicy() throws Exception {
        int ignored = 0;
        for (String url : EXTERNAL_URLS) {
            if (!url.contains("?") || driverProperties(url).getProperty("sslmode") != null) continue;
            ignored++;
            assertFalse(accepts(url), "the driver ignores the sslmode key of " + url);
        }
        assertTrue(ignored >= 3, "the sample must contain URLs whose key the driver ignores: " + ignored);
    }

    @Test
    void aLoopbackUrlTheMinosPolicyAcceptsNeverCarriesAModeKeyTheDriverWouldIgnore() throws Exception {
        int refusedForTheKey = 0;
        for (String url : LOOPBACK_URLS) {
            boolean carriesAKey = url.toLowerCase(Locale.ROOT).contains("sslmode") || url.contains("ssl%6Dode");
            if (accepts(url)) {
                if (carriesAKey) {
                    assertNotNull(driverProperties(url).getProperty("sslmode"),
                            "the driver ignores the sslmode key of " + url);
                }
            } else {
                assertTrue(carriesAKey, "a plain loopback URL must be accepted: " + url);
                assertEquals(null, driverProperties(url).getProperty("sslmode"),
                        "a refused URL must be one whose key the driver ignores: " + url);
                refusedForTheKey++;
            }
        }
        assertTrue(refusedForTheKey >= 2, "the sample must contain refused loopback URLs: " + refusedForTheKey);
    }

    private static boolean accepts(String url) {
        try {
            PostgresJdbcUrlPolicy.validate(url, false);
            return true;
        } catch (IOException refused) {
            return false;
        }
    }

    private static SslMode driverMode(String url) throws Exception {
        return SslMode.of(driverProperties(url));
    }

    private static Properties driverProperties(String url) {
        Properties parsed = Driver.parseURL(url, new Properties());
        assertNotNull(parsed, "the driver must understand " + url);
        return parsed;
    }
}
