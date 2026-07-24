package com.minos.adapter.scip;

import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipKind;
import com.minos.domain.RelationshipSearchCriteria;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolSearchCriteria;
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
        assertEquals("io.example.UserService", symbols.getFirst().qualifiedName());
        assertTrue(symbols.getFirst().providerReferences().stream()
                .anyMatch(reference -> reference.externalId().equals(RAW_USER_SERVICE)));
        assertEquals(1, queries.findSymbols(
                "project-1",
                SymbolSearchCriteria.qualifiedName("io.example.UserService", 10)
        ).size());

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
        assertEquals("UserService.findUser", symbol.qualifiedName());
        assertEquals("typescript", symbol.language());
        assertEquals(SymbolKind.METHOD, symbol.kind());
        assertTrue(symbol.providerReferences().stream()
                .anyMatch(reference -> reference.externalId().equals(rawSymbol)));
    }

    @Test
    void normalizesEveryScipRelationshipFlagAsASeparateFactualRelation() {
        String rawPort = "scip-java maven fixture 1.0 io/example/GreetingPort#";
        String rawImplementation = "scip-java maven fixture 1.0 io/example/GreetingService#";
        SymbolInformation port = SymbolInformation.newBuilder()
                .setSymbol(rawPort)
                .setDisplayName("GreetingPort")
                .setKind(SymbolInformation.Kind.Interface)
                .build();
        SymbolInformation implementation = SymbolInformation.newBuilder()
                .setSymbol(rawImplementation)
                .setDisplayName("GreetingService")
                .setKind(SymbolInformation.Kind.Class)
                .addRelationships(org.scip_code.scip.Relationship.newBuilder()
                        .setSymbol(rawPort)
                        .setIsReference(true)
                        .setIsImplementation(true)
                        .setIsTypeDefinition(true)
                        .setIsDefinition(true))
                .build();
        String relativePath = "src/main/java/io/example/GreetingService.java";
        Document document = Document.newBuilder()
                .setLanguage("java")
                .setRelativePath(relativePath)
                .setPositionEncoding(org.scip_code.scip.PositionEncoding.UTF16CodeUnitOffsetFromLineStart)
                .addSymbols(port)
                .addSymbols(implementation)
                .addOccurrences(occurrence(rawPort, 2, 0, 12, SymbolRole.Definition_VALUE))
                .addOccurrences(occurrence(rawImplementation, 4, 0, 15, SymbolRole.Definition_VALUE))
                .build();
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore();

        ScipIngestionReport report = new ScipIngestionAdapter().ingest(
                Index.newBuilder().addDocuments(document).build(),
                new ScipIngestionRequest(
                        "project-relationships",
                        "module-main",
                        "scip-java",
                        "0.13.1",
                        "run-relationships",
                        Map.of(relativePath, "file-greeting-service")
                ),
                store
        );

        assertEquals(1, report.providerRelationshipCount());
        assertEquals(4, report.providerRelationshipFactCount());
        assertEquals(4, report.relationshipCount());
        assertEquals(1, report.derivedRelationshipCount());
        assertEquals(4, report.resolvedRelationshipCount());
        assertEquals(0, report.unresolvedRelationshipCount());
        assertEquals(0, report.skippedRelationshipFactCount());
        assertEquals(0, report.duplicateRelationshipCount());

        SymbolQueryService symbols = new SymbolQueryService(store);
        var source = symbols.findSymbol("project-relationships", "GreetingService", 10).getFirst();
        var target = symbols.findSymbol("project-relationships", "GreetingPort", 10).getFirst();
        RelationshipQueryService relationships = new RelationshipQueryService(store);
        var outgoing = relationships.findRelationships(
                "project-relationships",
                RelationshipSearchCriteria.outgoing(
                        new CodeEntityRef(
                                CodeEntityType.SYMBOL,
                                source.id()
                        ),
                        Set.of(),
                        10
                )
        );

        assertEquals(Set.of(
                RelationshipKind.REFERENCES,
                RelationshipKind.IMPLEMENTS,
                RelationshipKind.TYPE_DEFINITION,
                RelationshipKind.DEFINITION,
                RelationshipKind.DEPENDS_ON
        ), outgoing.stream().map(Relationship::kind).collect(Collectors.toSet()));
        assertTrue(outgoing.stream().allMatch(relation -> relation.target().id().equals(target.id())));
        assertTrue(outgoing.stream()
                .filter(relation -> relation.kind() != RelationshipKind.DEPENDS_ON)
                .allMatch(relation -> relation.evidence().size() == 1));
        assertEquals(3, outgoing.stream()
                .filter(relation -> relation.kind() == RelationshipKind.DEPENDS_ON)
                .findFirst()
                .orElseThrow()
                .evidence()
                .size());
        assertEquals(1, relationships.findImplementations(
                "project-relationships",
                target.id(),
                10
        ).size());
    }

    @Test
    void preservesResolvableTargetNameWhenScipRelationshipTargetIsNotCatalogued() {
        String rawSource = "scip-java maven fixture 1.0 io/example/GreetingService#";
        String rawMissing = "scip-java maven missing 1.0 io/example/MissingPort#";
        SymbolInformation source = SymbolInformation.newBuilder()
                .setSymbol(rawSource)
                .setDisplayName("GreetingService")
                .setKind(SymbolInformation.Kind.Class)
                .addRelationships(org.scip_code.scip.Relationship.newBuilder()
                        .setSymbol(rawMissing)
                        .setIsImplementation(true))
                .addRelationships(org.scip_code.scip.Relationship.newBuilder()
                        .setIsReference(true))
                .addRelationships(org.scip_code.scip.Relationship.newBuilder()
                        .setSymbol(rawMissing))
                .build();
        String relativePath = "src/main/java/io/example/GreetingService.java";
        Document document = Document.newBuilder()
                .setLanguage("java")
                .setRelativePath(relativePath)
                .setPositionEncoding(org.scip_code.scip.PositionEncoding.UTF16CodeUnitOffsetFromLineStart)
                .addSymbols(source)
                .addOccurrences(occurrence(rawSource, 4, 0, 15, SymbolRole.Definition_VALUE))
                .build();
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore();

        ScipIngestionReport report = new ScipIngestionAdapter().ingest(
                Index.newBuilder().addDocuments(document).build(),
                new ScipIngestionRequest(
                        "project-unresolved-relationship",
                        "module-main",
                        "scip-java",
                        "0.13.1",
                        "run-unresolved-relationship",
                        Map.of(relativePath, "file-greeting-service")
                ),
                store
        );

        assertEquals(3, report.providerRelationshipCount());
        assertEquals(2, report.providerRelationshipFactCount());
        assertEquals(1, report.relationshipCount());
        assertEquals(1, report.derivedRelationshipCount());
        assertEquals(0, report.resolvedRelationshipCount());
        assertEquals(1, report.unresolvedRelationshipCount());
        assertEquals(1, report.skippedRelationshipFactCount());

        var sourceSymbol = new SymbolQueryService(store)
                .findSymbol("project-unresolved-relationship", "GreetingService", 10)
                .getFirst();
        var outgoing = new RelationshipQueryService(store).findOutgoing(
                "project-unresolved-relationship",
                new CodeEntityRef(
                        CodeEntityType.SYMBOL,
                        sourceSymbol.id()
                ),
                Set.of(RelationshipKind.IMPLEMENTS),
                10
        );

        assertEquals(1, outgoing.size());
        assertEquals("io.example.MissingPort", outgoing.getFirst().unresolvedTarget());
        assertEquals(ResolutionStatus.UNRESOLVED, outgoing.getFirst().resolutionStatus());
    }

    @Test
    void coalescesDuplicateProviderFactsWhileReportingThem() {
        String rawPort = "scip-java maven fixture 1.0 io/example/Port#";
        String rawImplementation = "scip-java maven fixture 1.0 io/example/Implementation#";
        var providerRelationship = org.scip_code.scip.Relationship.newBuilder()
                .setSymbol(rawPort)
                .setIsImplementation(true)
                .build();
        SymbolInformation source = SymbolInformation.newBuilder()
                .setSymbol(rawImplementation)
                .setDisplayName("Implementation")
                .setKind(SymbolInformation.Kind.Class)
                .addRelationships(providerRelationship)
                .addRelationships(providerRelationship)
                .build();
        SymbolInformation target = SymbolInformation.newBuilder()
                .setSymbol(rawPort)
                .setDisplayName("Port")
                .setKind(SymbolInformation.Kind.Interface)
                .build();
        String relativePath = "src/main/java/io/example/Implementation.java";
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore();

        ScipIngestionReport report = new ScipIngestionAdapter().ingest(
                Index.newBuilder().addDocuments(Document.newBuilder()
                        .setLanguage("java")
                        .setRelativePath(relativePath)
                        .addSymbols(source)
                        .addSymbols(target)
                        .build()).build(),
                new ScipIngestionRequest(
                        "project-duplicates",
                        "module-main",
                        "scip-java",
                        "0.13.1",
                        "run-duplicates",
                        Map.of(relativePath, "file-implementation")
                ),
                store
        );

        assertEquals(2, report.providerRelationshipCount());
        assertEquals(2, report.providerRelationshipFactCount());
        assertEquals(1, report.relationshipCount());
        assertEquals(1, report.derivedRelationshipCount());
        assertEquals(1, report.duplicateRelationshipCount());
        assertEquals(0, report.skippedRelationshipFactCount());
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
