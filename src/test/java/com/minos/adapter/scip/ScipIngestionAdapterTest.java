package com.minos.adapter.scip;

import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.query.SymbolQueryService;
import com.minos.store.InMemoryCodeKnowledgeStore;
import org.junit.jupiter.api.Test;
import org.scip_code.scip.Document;
import org.scip_code.scip.Index;
import org.scip_code.scip.Occurrence;
import org.scip_code.scip.SingleLineRange;
import org.scip_code.scip.SymbolInformation;
import org.scip_code.scip.SymbolRole;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScipIngestionAdapterTest {

    private static final String RAW_USER_SERVICE =
            "scip-java maven fixture 1.0 io/example/UserService#";

    @Test
    void ingestsResolvedAndUnresolvedOccurrencesIntoMinosContracts() {
        SymbolInformation userService = SymbolInformation.newBuilder()
                .setSymbol(RAW_USER_SERVICE)
                .setDisplayName("UserService")
                .setKind(SymbolInformation.Kind.Class)
                .build();

        Document document = Document.newBuilder()
                .setLanguage("java")
                .setRelativePath("src/main/java/io/example/UserService.java")
                .setPositionEncoding(org.scip_code.scip.PositionEncoding.UTF16CodeUnitOffsetFromLineStart)
                .addSymbols(userService)
                .addOccurrences(occurrence(RAW_USER_SERVICE, 4, 0, 11, SymbolRole.Definition_VALUE))
                .addOccurrences(occurrence(RAW_USER_SERVICE, 18, 8, 19, SymbolRole.ReadAccess_VALUE))
                .addOccurrences(occurrence(
                        "scip-java maven missing 1.0 io/example/MissingType#",
                        22,
                        4,
                        15,
                        SymbolRole.ReadAccess_VALUE))
                .build();

        Index index = Index.newBuilder()
                .addDocuments(document)
                .build();

        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore();
        ScipIngestionReport report = new ScipIngestionAdapter().ingest(
                index,
                new ScipIngestionRequest(
                        "project-1",
                        "module-main",
                        "scip-java",
                        "0.13.1",
                        "run-1",
                        Map.of("src/main/java/io/example/UserService.java", "file-user-service")
                ),
                store
        );

        assertEquals(1, report.catalogSymbolCount());
        assertEquals(1, report.normalizedSymbolCount());
        assertEquals(0, report.skippedSymbolCount());
        assertEquals(3, report.occurrenceCount());
        assertEquals(2, report.resolvedOccurrenceCount());
        assertEquals(1, report.unresolvedOccurrenceCount());
        assertEquals(0, report.skippedOccurrenceCount());
        assertEquals(1.0 / 3.0, report.unresolvedOccurrenceRate(), 0.0001);

        SymbolQueryService queries = new SymbolQueryService(store);
        var symbols = queries.findSymbol("project-1", "UserService", 10);

        assertEquals(1, symbols.size());
        assertEquals(SymbolIdentityQuality.STRUCTURAL_FALLBACK, symbols.getFirst().identityQuality());
        assertNull(symbols.getFirst().qualifiedName());
        assertTrue(symbols.getFirst().providerReferences().stream()
                .anyMatch(reference -> reference.externalId().equals(RAW_USER_SERVICE)));

        var usages = queries.findUsages("project-1", symbols.getFirst().id(), 10);
        assertEquals(1, usages.size());
        assertEquals(19, usages.getFirst().location().startLine());
        assertTrue(usages.getFirst().isResolved());
    }

    @Test
    void resolvesReusedLocalSymbolIdsWithinTheirOwnDocument() {
        Document firstDocument = localSymbolDocument("First.java", "first");
        Document secondDocument = localSymbolDocument("Second.java", "second");
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore();

        ScipIngestionReport report = new ScipIngestionAdapter().ingest(
                Index.newBuilder()
                        .addDocuments(firstDocument)
                        .addDocuments(secondDocument)
                        .build(),
                new ScipIngestionRequest(
                        "project-local-scope",
                        "module-main",
                        "scip-java",
                        "0.13.1",
                        "run-local-scope",
                        Map.of("First.java", "file-first", "Second.java", "file-second")
                ),
                store
        );

        assertEquals(2, report.catalogSymbolCount());
        assertEquals(2, report.normalizedSymbolCount());
        assertEquals(4, report.resolvedOccurrenceCount());
        assertEquals(0, report.unresolvedOccurrenceCount());

        SymbolQueryService queries = new SymbolQueryService(store);
        var first = queries.findSymbol("project-local-scope", "first", 10).getFirst();
        var second = queries.findSymbol("project-local-scope", "second", 10).getFirst();

        assertEquals("file-first", queries.findUsages("project-local-scope", first.id(), 10)
                .getFirst().location().fileId());
        assertEquals("file-second", queries.findUsages("project-local-scope", second.id(), 10)
                .getFirst().location().fileId());
    }

    @Test
    void derivesMinimalMetadataWhenTypeScriptOmitsDisplayNameAndLanguage() {
        String rawSymbol =
                "scip-typescript npm fixture 1.0.0 src/`user-service.ts`/UserService#findUser().";
        Document document = Document.newBuilder()
                .setRelativePath("src/user-service.ts")
                .addSymbols(SymbolInformation.newBuilder().setSymbol(rawSymbol))
                .addOccurrences(occurrence(rawSymbol, 4, 2, 10, SymbolRole.Definition_VALUE))
                .build();
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore();

        ScipIngestionReport report = new ScipIngestionAdapter().ingest(
                Index.newBuilder().addDocuments(document).build(),
                new ScipIngestionRequest(
                        "project-typescript",
                        "module-main",
                        "scip-typescript",
                        "0.4.0",
                        "run-typescript",
                        Map.of("src/user-service.ts", "file-user-service")
                ),
                store
        );

        assertEquals(1, report.normalizedSymbolCount());
        var symbol = new SymbolQueryService(store)
                .findSymbol("project-typescript", "findUser", 10)
                .getFirst();
        assertEquals("findUser", symbol.name());
        assertEquals("typescript", symbol.language());
        assertEquals(SymbolKind.OTHER, symbol.kind());
        assertTrue(symbol.providerReferences().stream()
                .anyMatch(reference -> reference.externalId().equals(rawSymbol)));
    }

    private static Document localSymbolDocument(String relativePath, String displayName) {
        SymbolInformation local = SymbolInformation.newBuilder()
                .setSymbol("local 0")
                .setDisplayName(displayName)
                .setKind(SymbolInformation.Kind.Variable)
                .build();
        return Document.newBuilder()
                .setLanguage("java")
                .setRelativePath(relativePath)
                .setPositionEncoding(org.scip_code.scip.PositionEncoding.UTF16CodeUnitOffsetFromLineStart)
                .addSymbols(local)
                .addOccurrences(occurrence("local 0", 0, 0, displayName.length(), SymbolRole.Definition_VALUE))
                .addOccurrences(occurrence("local 0", 1, 0, displayName.length(), SymbolRole.ReadAccess_VALUE))
                .build();
    }

    private static Occurrence occurrence(
            String rawSymbol,
            int zeroBasedLine,
            int startCharacter,
            int endCharacter,
            int symbolRoles) {
        return Occurrence.newBuilder()
                .setSymbol(rawSymbol)
                .setSymbolRoles(symbolRoles)
                .setSingleLineRange(SingleLineRange.newBuilder()
                        .setLine(zeroBasedLine)
                        .setStartCharacter(startCharacter)
                        .setEndCharacter(endCharacter))
                .build();
    }
}
