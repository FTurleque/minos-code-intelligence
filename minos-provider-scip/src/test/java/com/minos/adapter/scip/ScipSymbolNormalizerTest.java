package com.minos.adapter.scip;

import com.minos.domain.PositionEncoding;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import org.junit.jupiter.api.Test;
import org.scip_code.scip.SymbolInformation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScipSymbolNormalizerTest {

    private static final String PROJECT_ONE = "project-1";
    private static final String JAVA_PROVIDER = "scip-java";
    private static final String OTHER_PROVIDER = "scip-other";
    private static final String RUN_ONE = "run-1";

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
    void disambiguatesSameQualifiedSymbolAcrossNestedProviderScopesWithoutChangingRootContract() {
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

        var app = normalizer.normalize(
                fact,
                "project-1",
                null,
                "file-app",
                "ui/app/src/main/java/io/example/UserService.java",
                "ui/app",
                new SymbolLocation("file-app", 8, 0, 40, 1, PositionEncoding.UTF16_CODE_UNITS),
                "scip-java",
                "0.13.1",
                "run-1:scip-java",
                false
        ).orElseThrow();
        var lib = normalizer.normalize(
                fact,
                "project-1",
                null,
                "file-lib",
                "ui/lib/src/main/java/io/example/UserService.java",
                "ui/lib",
                new SymbolLocation("file-lib", 8, 0, 40, 1, PositionEncoding.UTF16_CODE_UNITS),
                "scip-java",
                "0.13.1",
                "run-1:scip-java",
                false
        ).orElseThrow();
        var root = normalizer.normalize(
                fact,
                "project-1",
                null,
                "file-root",
                "src/main/java/io/example/UserService.java",
                "",
                new SymbolLocation("file-root", 8, 0, 40, 1, PositionEncoding.UTF16_CODE_UNITS),
                "scip-java",
                "0.13.1",
                "run-1:scip-java",
                false
        ).orElseThrow();
        var historicalRoot = normalizer.normalize(
                fact,
                "project-1",
                null,
                "file-root",
                new SymbolLocation("file-root", 8, 0, 40, 1, PositionEncoding.UTF16_CODE_UNITS),
                "scip-java",
                "0.13.1",
                "run-1:scip-java",
                false
        ).orElseThrow();

        assertEquals("io.example.UserService", app.qualifiedName());
        assertEquals(app.qualifiedName(), lib.qualifiedName());
        assertNotEquals(app.id(), lib.id());
        assertNotEquals(app.symbolKey(), lib.symbolKey());
        assertEquals(historicalRoot.id(), root.id(),
                "empty provider scope must preserve historical root-level identities");
        assertEquals(historicalRoot.symbolKey(), root.symbolKey());
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

    /**
     * Q9 : le seul producteur de symboles dérive l'identifiant de la clé ({@code id = sha256(projet, clé)}), donc
     * deux symboles du même projet ont la même clé si et seulement s'ils ont le même identifiant. Un instantané
     * refuse deux symboles de même identifiant : deux clés identiques n'arrivent jamais jusqu'à la fabrique
     * sémantique (voir {@code SemanticDocumentKeysReachabilityTest}).
     */
    @Test
    void theIdOfASymbolIsAFunctionOfItsKeyAndTheKeyOfItsIdentity() {
        ScipSymbolFact fact = new ScipSymbolFact(
                "scip-java maven example 1.0 io/example/UserService#", "UserService", SymbolInformation.Kind.Class,
                "final class UserService", "", "src/main/java/io/example/UserService.java", "java", false);
        ScipSymbolFact otherKind = new ScipSymbolFact(
                fact.rawSymbol(), fact.displayName(), SymbolInformation.Kind.Interface, fact.signature(),
                fact.enclosingRawSymbol(), fact.relativePath(), fact.language(), false);
        ScipSymbolFact external = new ScipSymbolFact(
                fact.rawSymbol(), fact.displayName(), SymbolInformation.Kind.Class, fact.signature(),
                fact.enclosingRawSymbol(), "", fact.language(), true);
        SymbolLocation location = new SymbolLocation("file", 8, 0, 40, 1, PositionEncoding.UTF16_CODE_UNITS);

        List<Symbol> symbols = List.of(
                normalizer.normalize(fact, PROJECT_ONE, null, "file", location, JAVA_PROVIDER, "1", RUN_ONE, false).orElseThrow(),
                normalizer.normalize(fact, PROJECT_ONE, null, "file", location, OTHER_PROVIDER, "2", "run-2", false).orElseThrow(),
                normalizer.normalize(otherKind, PROJECT_ONE, null, "file", location, JAVA_PROVIDER, "1", RUN_ONE, false).orElseThrow(),
                normalizer.normalize(external, PROJECT_ONE, null, null, null, JAVA_PROVIDER, "1", RUN_ONE, false).orElseThrow(),
                normalizer.normalize(external, PROJECT_ONE, null, null, null, OTHER_PROVIDER, "1", RUN_ONE, false).orElseThrow(),
                normalizer.normalize(fact, "project-2", null, "file", location, JAVA_PROVIDER, "1", RUN_ONE, false).orElseThrow());

        for (Symbol left : symbols) {
            for (Symbol right : symbols) {
                assertEquals(left.symbolKey().equals(right.symbolKey()), left.id().equals(right.id()),
                        "same key if and only if same id: " + left.symbolKey() + " / " + right.symbolKey());
            }
        }
    }

    @Test
    void aNullFactIsRefusedWithAMessageNotWithALaterNullPointer() {
        org.junit.jupiter.api.Assertions.assertEquals("fact",
                org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                        () -> normalizer.normalize(null, PROJECT_ONE, null, "file", null, JAVA_PROVIDER, "1", RUN_ONE, false))
                        .getMessage());
    }
}
