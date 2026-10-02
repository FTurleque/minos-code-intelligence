package com.minos.query;

import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.Relationship;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q12 : les heuristiques de tests liés ne retirent que de vrais suffixes et ne prennent pour
 * répertoire de tests que ceux de la convention (source sets {@code src/test}, {@code src/it},
 * répertoires racine {@code test}/{@code tests}, {@code __tests__}), jamais un dossier « test »
 * quelconque du chemin.
 */
class RelatedTestHeuristicsTest {

    private final RelatedTestDerivationService service = new RelatedTestDerivationService();

    // --- suffixes : ne tronquer que de vrais suffixes -------------------------------------

    @ParameterizedTest
    @CsvSource({
            "Audit,Aud",
            "Commit,Comm",
            "Limit,Lim",
            "Permit,Perm",
            "Submit,Subm",
            "Visit,Vis",
            "Latest,La",
            "Contest,Con",
            "Protest,Pro",
            "AUDIT,AUD"
    })
    void aTestSideNameThatOnlyLooksLikeASuffixIsNeverTruncated(String testName, String truncated) {
        Symbol test = symbol("test", testName, SymbolKind.CLASS,
                "src/test/java/com/acme/" + testName + ".java");
        Symbol production = symbol("production", truncated, SymbolKind.CLASS,
                "src/main/java/com/acme/" + truncated + ".java");

        assertTrue(service.derive(List.of(test, production), List.of(), List.of()).isEmpty(),
                testName + " ne doit pas etre rattache a " + truncated);
    }

    @ParameterizedTest
    @CsvSource({
            "Audit,AuditTest",
            "Commit,CommitTest",
            "Limit,LimitTest",
            "Audit,AuditTests",
            "Commit,CommitIT",
            "Limit,LimitIT",
            "Audit,AuditSpec",
            "Audit,AuditSpecs",
            "Audit,AuditSpecification",
            "Latest,LatestTest",
            "Contest,ContestTest",
            "HttpClient,HttpClientIT",
            "Http2,Http2IT",
            "IOUtil,IOUtilTest",
            "Audit,Audit_test",
            "Audit,audit-it",
            "Audit,Audit.spec"
    })
    void aRealSuffixIsStrippedAtAWordBoundary(String productionName, String testName) {
        Symbol test = symbol("test", testName, SymbolKind.CLASS,
                "src/test/java/com/acme/" + testName + ".java");
        Symbol production = symbol("production", productionName, SymbolKind.CLASS,
                "src/main/java/com/acme/" + productionName + ".java");

        List<Relationship> related = service.derive(List.of(test, production), List.of(), List.of());

        assertEquals(1, related.size(), testName + " doit rester rattache a " + productionName);
        assertEquals(production.id(), related.getFirst().target().id());
    }

    @Test
    void aTestNamedLikeAProductionSymbolPlusItIsNotLinkedToTheTruncatedProductionSymbol() {
        // AuditTest garde son rattachement a Audit et n'en obtient AUCUN a Aud.
        Symbol test = symbol("test", "AuditTest", SymbolKind.CLASS, "src/test/java/com/acme/AuditTest.java");
        Symbol audit = symbol("audit", "Audit", SymbolKind.CLASS, "src/main/java/com/acme/Audit.java");
        Symbol aud = symbol("aud", "Aud", SymbolKind.CLASS, "src/main/java/com/acme/Aud.java");

        List<Relationship> related = service.derive(List.of(test, audit, aud), List.of(), List.of());

        assertEquals(List.of(audit.id()), related.stream().map(r -> r.target().id()).toList());
    }

    @Test
    void aCamelCaseItWordIsStillASuffixButALowerCaseTailOfAWordIsNot() {
        // FooIt : « It » est un mot a part entiere (frontiere de casse) ; « Audit » se termine par « it » en minuscules.
        Symbol wordIt = symbol("word-it", "FooIt", SymbolKind.CLASS, "src/test/java/com/acme/FooIt.java");
        Symbol foo = symbol("foo", "Foo", SymbolKind.CLASS, "src/main/java/com/acme/Foo.java");

        assertEquals(1, service.derive(List.of(wordIt, foo), List.of(), List.of()).size());
    }

    // --- répertoires de tests : la convention, pas le mot n'importe où ----------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "src/test/java/com/acme/FooTest.java",
            "minos-engine/src/test/java/com/acme/FooTest.java",
            "minos-engine\\src\\test\\java\\com\\acme\\FooTest.java",
            "SRC/TEST/java/com/acme/FooTest.java",
            "src/it/java/com/acme/FooIT.java",
            "minos-app/src/it/java/com/acme/FooIT.java",
            "src/integrationTest/java/com/acme/FooIT.java",
            "src/integration-test/java/com/acme/FooIT.java",
            "src/test/kotlin/com/acme/FooTest.kt",
            "src/test/java/com/acme/test/Helper.java",
            "test/com/acme/FooTest.java",
            "tests/test_foo.py",
            "packages/foo/test/helper.ts",
            "crates/foo/tests/integration.rs",
            "src/__tests__/foo.ts",
            "web/app/__tests__/foo.ts",
            "src/foo.test.ts",
            "lib/foo.spec.ts"
    })
    void theProjectTestConventionsAreTestPaths(String path) {
        assertTrue(RelatedTestDerivationService.isTestPath(path), path);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "src/main/java/com/acme/test/Support.java",
            "src/main/java/com/acme/tests/Support.java",
            "src/main/java/com/acme/it/Support.java",
            "com/acme/test/Support.java",
            "com/acme/tests/Support.java",
            "minos-engine/src/main/java/com/acme/test/Support.java",
            "latest/Widget.java",
            "src/main/java/com/acme/latest/Widget.java",
            "contest/Runner.py",
            "src/main/java/com/acme/contest/Runner.java",
            "attest/checks.ts",
            "src/main/java/com/acme/protests/Vote.java",
            "src/main/java/com/acme/Foo.java",
            "src/Test.java",
            "src/testing/Foo.java",
            "src/test-support/Foo.java",
            "docs/tester/notes.md"
    })
    void aDirectoryThatMerelyContainsTheWordTestIsNotATestPath(String path) {
        assertFalse(RelatedTestDerivationService.isTestPath(path), path);
    }

    @Test
    void aProductionClassInAPackageNamedTestCanBeTheTargetOfATest() {
        // Sous /test/ n'importe ou, Support etait pris pour un symbole de test et ne pouvait plus etre une cible.
        Symbol production = symbol("production", "Support", SymbolKind.CLASS,
                "src/main/java/com/acme/test/Support.java");
        Symbol test = symbol("test", "SupportTest", SymbolKind.CLASS,
                "src/test/java/com/acme/test/SupportTest.java");

        List<Relationship> related = service.derive(List.of(production, test), List.of(), List.of());

        assertEquals(1, related.size());
        assertEquals(test.id(), related.getFirst().source().id());
        assertEquals(production.id(), related.getFirst().target().id());
    }

    @Test
    void aProductionClassInAPackageNamedTestIsNeverATestAnchor() {
        // CommitTest est ici une classe de PRODUCTION (paquet com.acme.test) : elle ne doit pas devenir la source d'une
        // relation vers Commit.
        Symbol helper = symbol("helper", "CommitTest", SymbolKind.CLASS,
                "src/main/java/com/acme/test/CommitTest.java");
        Symbol commit = symbol("commit", "Commit", SymbolKind.CLASS, "src/main/java/com/acme/Commit.java");

        assertTrue(service.derive(List.of(helper, commit), List.of(), List.of()).isEmpty());
    }

    @Test
    void anIntegrationTestSourceSetIsATestDirectory() {
        Symbol test = symbol("test", "OrderIT", SymbolKind.CLASS, "src/it/java/com/acme/OrderIT.java");
        Symbol production = symbol("production", "Order", SymbolKind.CLASS,
                "src/main/java/com/acme/Order.java");

        List<Relationship> related = service.derive(List.of(test, production), List.of(), List.of());

        assertEquals(1, related.size());
        assertEquals(test.id(), related.getFirst().source().id());
    }

    private static Symbol symbol(String id, String name, SymbolKind kind, String fileId) {
        SymbolLocation location = new SymbolLocation(
                fileId, 1, 0, 1, 8, PositionEncoding.UTF16_CODE_UNITS);
        return new Symbol(
                id, "key-" + id, SymbolIdentityQuality.STRUCTURAL_FALLBACK, "project-1", "main",
                fileId, null, kind, name, "com.acme." + name, null, "java", location,
                ResolutionStatus.RESOLVED,
                new Origin("fixture", "TEST", "1", "run-1", OriginType.OTHER),
                false, false, Set.of());
    }
}
