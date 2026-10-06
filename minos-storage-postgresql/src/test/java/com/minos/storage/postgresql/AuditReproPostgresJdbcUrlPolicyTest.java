package com.minos.storage.postgresql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Reproduction volontairement ROUGE du constat MINOS-AUD-B07 (audit 2026-10, {@code docs/audit/constats.md}).
 *
 * <p>La politique normalise le nom du paramètre en minuscules avant de l'accepter, mais le pilote pgjdbc
 * (42.7.13, vérifié pendant l'audit) ne reconnaît que {@code sslmode} : {@code SSLMODE=verify-full} est ignoré et
 * la connexion retombe sur le mode par défaut, sans vérification de certificat. Sautés par défaut ; rejouer avec
 * {@code -Dminos.audit.repro=true}.
 */
@EnabledIfSystemProperty(named = "minos.audit.repro", matches = "true")
class AuditReproPostgresJdbcUrlPolicyTest {

    @Test
    void anExternalUrlWithAnUpperCaseSslModeKeyIsRefusedBecauseTheDriverWouldIgnoreIt() {
        assertThrows(java.io.IOException.class, () -> PostgresJdbcUrlPolicy.validate(
                "jdbc:postgresql://db.example.com:5432/minos?SSLMODE=verify-full", false));
    }

    @Test
    void anExternalUrlWithAPercentEncodedSslModeKeyIsRefusedBecauseTheDriverWouldIgnoreIt() {
        assertThrows(java.io.IOException.class, () -> PostgresJdbcUrlPolicy.validate(
                "jdbc:postgresql://db.example.com:5432/minos?ssl%6Dode=verify-full", false));
    }
}
