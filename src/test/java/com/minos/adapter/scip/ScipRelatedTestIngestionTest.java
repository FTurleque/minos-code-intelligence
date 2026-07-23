package com.minos.adapter.scip;

import com.minos.domain.Evidence;
import com.minos.domain.EvidenceType;
import com.minos.domain.InformationNature;
import com.minos.domain.RelationshipKind;
import com.minos.query.RelationshipQueryService;
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
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScipRelatedTestIngestionTest {

    private static final String PRODUCTION =
            "scip-java maven fixture 1.0 com/acme/Widget#";
    private static final String TEST =
            "scip-java maven fixture 1.0 com/acme/WidgetTest#";

    @Test
    void derivesAndStoresRelatedTestFromRealScipDocuments() {
        String productionPath = "src/main/java/com/acme/Widget.java";
        String testPath = "src/test/java/com/acme/WidgetTest.java";
        Document production = document(
                productionPath,
                symbol(PRODUCTION, "Widget"),
                definition(PRODUCTION, 2)
        );
        Document test = Document.newBuilder(document(
                        testPath,
                        symbol(TEST, "WidgetTest"),
                        definition(TEST, 4)))
                .addOccurrences(reference(PRODUCTION, 8))
                .build();
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore();

        ScipIngestionReport report = new ScipIngestionAdapter().ingest(
                Index.newBuilder().addDocuments(production).addDocuments(test).build(),
                new ScipIngestionRequest(
                        "project-1", "main", "scip-java", "0.13.1", "run-1",
                        Map.of(productionPath, productionPath, testPath, testPath)
                ),
                store
        );

        assertEquals(1, report.derivedRelationshipCount());
        assertEquals(1, report.relatedTestRelationshipCount());
        var symbols = new SymbolQueryService(store);
        var productionSymbol = symbols.findSymbol("project-1", "Widget", 10).stream()
                .filter(symbol -> "Widget".equals(symbol.name()))
                .findFirst()
                .orElseThrow();
        var related = new RelationshipQueryService(store).findRelatedTests(
                "project-1", productionSymbol.id(), 10);

        assertEquals(1, related.size());
        assertEquals(RelationshipKind.RELATED_TEST, related.getFirst().kind());
        assertEquals(InformationNature.DERIVED, related.getFirst().nature());
        assertEquals(Set.of(
                EvidenceType.DIRECT_REFERENCE,
                EvidenceType.NAMING_CONVENTION,
                EvidenceType.PACKAGE_PROXIMITY,
                EvidenceType.TEST_LOCATION
        ), related.getFirst().evidence().stream()
                .map(Evidence::type)
                .collect(Collectors.toSet()));
    }

    private static Document document(
            String path,
            SymbolInformation symbol,
            Occurrence definition
    ) {
        return Document.newBuilder()
                .setLanguage("java")
                .setRelativePath(path)
                .setPositionEncoding(
                        org.scip_code.scip.PositionEncoding.UTF16CodeUnitOffsetFromLineStart)
                .addSymbols(symbol)
                .addOccurrences(definition)
                .build();
    }

    private static SymbolInformation symbol(String raw, String name) {
        return SymbolInformation.newBuilder()
                .setSymbol(raw)
                .setDisplayName(name)
                .setKind(SymbolInformation.Kind.Class)
                .build();
    }

    private static Occurrence definition(String raw, int line) {
        return occurrence(raw, line, SymbolRole.Definition_VALUE);
    }

    private static Occurrence reference(String raw, int line) {
        return occurrence(raw, line, 0);
    }

    private static Occurrence occurrence(String raw, int line, int roles) {
        return Occurrence.newBuilder()
                .setSymbol(raw)
                .setSymbolRoles(roles)
                .setSingleLineRange(SingleLineRange.newBuilder()
                        .setLine(line)
                        .setStartCharacter(0)
                        .setEndCharacter(6))
                .build();
    }
}
