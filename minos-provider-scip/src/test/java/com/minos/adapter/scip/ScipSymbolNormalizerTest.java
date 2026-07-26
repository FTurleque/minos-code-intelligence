package com.minos.adapter.scip;

import com.minos.domain.PositionEncoding;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import org.junit.jupiter.api.Test;
import org.scip_code.scip.SymbolInformation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals("io.example.UserService", symbol.qualifiedName());
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
        assertEquals("java.lang.String", symbol.qualifiedName());
        assertTrue(symbol.symbolKey().startsWith("minos:provider:"));
        assertTrue(symbol.external());
    }

    @Test
    void keepsQualifiedStructuralIdentityStableAcrossFileMovesWhenSignatureIsAvailable() {
        ScipSymbolFact beforeMove = new ScipSymbolFact(
                "scip-java maven example 1.0 io/example/UserService#",
                "UserService",
                SymbolInformation.Kind.Class,
                "final class UserService",
                "",
                "src/main/java/io/example/UserService.java",
                "java",
                false
        );
        ScipSymbolFact afterMove = new ScipSymbolFact(
                beforeMove.rawSymbol(),
                beforeMove.displayName(),
                beforeMove.kind(),
                beforeMove.signature(),
                beforeMove.enclosingRawSymbol(),
                "src/generated/java/io/example/UserService.java",
                beforeMove.language(),
                false
        );

        var first = normalizer.normalize(
                beforeMove,
                "project-1",
                "module-main",
                "file-before",
                new SymbolLocation("file-before", 8, 0, 40, 1, PositionEncoding.UTF16_CODE_UNITS),
                "scip-java",
                "0.13.1",
                "run-1",
                false
        ).orElseThrow();
        var second = normalizer.normalize(
                afterMove,
                "project-1",
                "module-main",
                "file-after",
                new SymbolLocation("file-after", 18, 0, 50, 1, PositionEncoding.UTF16_CODE_UNITS),
                "scip-java",
                "0.13.1",
                "run-2",
                false
        ).orElseThrow();

        assertEquals(SymbolIdentityQuality.STRUCTURAL_FALLBACK, first.identityQuality());
        assertEquals(first.symbolKey(), second.symbolKey());
        assertEquals(first.id(), second.id());
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
