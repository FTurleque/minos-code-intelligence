package io.github.fturleque.minos.adapter.scip;

import org.junit.jupiter.api.Test;
import org.scip_code.scip.Document;
import org.scip_code.scip.Index;
import org.scip_code.scip.Signature;
import org.scip_code.scip.SymbolInformation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScipSymbolCatalogTest {

    @Test
    void catalogsDocumentAndExternalSymbolsWithoutNormalizingTheirIdentity() {
        String localSymbol = "scip-java maven example 1.0 io/example/UserService#";
        String externalSymbol = "scip-java maven external 1.0 java/lang/String#";

        SymbolInformation local = SymbolInformation.newBuilder()
                .setSymbol(localSymbol)
                .setDisplayName("UserService")
                .setKind(SymbolInformation.Kind.Class)
                .setSignatureDocumentation(Signature.newBuilder()
                        .setLanguage("java")
                        .setText("final class UserService"))
                .build();

        SymbolInformation external = SymbolInformation.newBuilder()
                .setSymbol(externalSymbol)
                .setDisplayName("String")
                .setKind(SymbolInformation.Kind.Class)
                .build();

        Index index = Index.newBuilder()
                .addDocuments(Document.newBuilder()
                        .setLanguage("java")
                        .setRelativePath("src/main/java/io/example/UserService.java")
                        .addSymbols(local))
                .addExternalSymbols(external)
                .build();

        ScipSymbolCatalog catalog = ScipSymbolCatalog.from(index);

        assertEquals(2, catalog.size());

        ScipSymbolFact localFact = catalog.find(
                "src/main/java/io/example/UserService.java",
                localSymbol
        ).orElseThrow();
        assertEquals("UserService", localFact.displayName());
        assertEquals("final class UserService", localFact.signature());
        assertEquals("src/main/java/io/example/UserService.java", localFact.relativePath());
        assertFalse(localFact.external());

        ScipSymbolFact externalFact = catalog.find("", externalSymbol).orElseThrow();
        assertEquals("String", externalFact.displayName());
        assertTrue(externalFact.external());
    }

    @Test
    void scopesScipLocalSymbolsByDocument() {
        SymbolInformation firstLocal = SymbolInformation.newBuilder()
                .setSymbol("local 0")
                .setDisplayName("first")
                .setKind(SymbolInformation.Kind.Variable)
                .build();
        SymbolInformation secondLocal = firstLocal.toBuilder()
                .setDisplayName("second")
                .build();

        Index index = Index.newBuilder()
                .addDocuments(Document.newBuilder()
                        .setLanguage("java")
                        .setRelativePath("First.java")
                        .addSymbols(firstLocal))
                .addDocuments(Document.newBuilder()
                        .setLanguage("java")
                        .setRelativePath("Second.java")
                        .addSymbols(secondLocal))
                .build();

        ScipSymbolCatalog catalog = ScipSymbolCatalog.from(index);

        assertEquals(2, catalog.size());
        assertEquals("first", catalog.find("First.java", "local 0").orElseThrow().displayName());
        assertEquals("second", catalog.find("Second.java", "local 0").orElseThrow().displayName());
    }
}
