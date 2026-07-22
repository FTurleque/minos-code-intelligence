package io.github.fturleque.minos.adapter.scip;

import io.github.fturleque.minos.domain.SymbolIdentityQuality;
import io.github.fturleque.minos.query.SymbolQueryService;
import io.github.fturleque.minos.store.InMemoryCodeKnowledgeStore;
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
