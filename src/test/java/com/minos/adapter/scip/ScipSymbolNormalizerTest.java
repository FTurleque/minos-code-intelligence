package com.minos.adapter.scip;

import com.minos.domain.PositionEncoding;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import org.junit.jupiter.api.Test;
import org.scip_code.scip.SymbolInformation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScipSymbolNormalizerTest {

    private final ScipSymbolNormalizer normalizer = new ScipSymbolNormalizer();

    @Test
    void createsProviderIndependentStructuralFallbackForLocalSymbol() {
        ScipSymbolFact fact = new ScipSymbolFact(
                "scip-java maven example 1.0 io/example/UserService#",
                "UserService",
                SymbolInformation.Kind.Class,
                "final class UserService",
                "",
                "src/main/java/io/example/UserService.java",
                "java",
                false
        );
        SymbolLocation location = new SymbolLocation(
                "file-user-service",
                8,
                0,
                40,
                1,
                PositionEncoding.UTF16_CODE_UNITS
        );

        var symbol = normalizer.normalize(
                fact,
                "project-1",
                "module-main",
                "file-user-service",
                location,
                "scip-java",
                "0.13.1",
                "run-1",
                false
        ).orElseThrow();

        assertEquals(SymbolIdentityQuality.STRUCTURAL_FALLBACK, symbol.identityQuality());
        assertEquals(SymbolKind.CLASS, symbol.kind());
        assertEquals("UserService", symbol.name());
        assertNull(symbol.qualifiedName());
        assertTrue(symbol.symbolKey().startsWith("minos:structural:"));
        assertTrue(symbol.providerReferences().stream()
                .anyMatch(reference -> reference.externalId().equals(fact.rawSymbol())));
        assertFalse(symbol.external());
    }

    @Test
    void marksExternalIdentityAsProviderScopedFallback() {
        ScipSymbolFact fact = new ScipSymbolFact(
                "scip-java maven jdk 25 java/lang/String#",
                "String",
                SymbolInformation.Kind.Class,
                "final class String",
                "",
                "",
                "java",
                true
        );

        var symbol = normalizer.normalize(
                fact,
                "project-1",
                null,
                null,
                null,
                "scip-java",
                "0.13.1",
                "run-1",
                false
        ).orElseThrow();

        assertEquals(SymbolIdentityQuality.PROVIDER_SCOPED_FALLBACK, symbol.identityQuality());
        assertTrue(symbol.symbolKey().startsWith("minos:provider:"));
        assertTrue(symbol.external());
    }

    @Test
    void refusesToInventNameOrLanguage() {
        ScipSymbolFact fact = new ScipSymbolFact(
                "local 1",
                "",
                SymbolInformation.Kind.Variable,
                "",
                "",
                "src/main/java/io/example/UserService.java",
                "java",
                false
        );

        assertTrue(normalizer.normalize(
                fact,
                "project-1",
                "module-main",
                "file-user-service",
                null,
                "scip-java",
                "0.13.1",
                "run-1",
                false
        ).isEmpty());
    }
}
