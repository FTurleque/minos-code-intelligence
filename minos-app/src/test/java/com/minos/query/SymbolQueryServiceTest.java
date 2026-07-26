package com.minos.query;

import com.minos.domain.OccurrenceRole;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.ProviderReference;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.ResolvedSymbolReference;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.domain.SymbolOccurrence;
import com.minos.domain.SymbolSearchCriteria;
import com.minos.domain.UnresolvedSymbolReference;
import com.minos.output.SymbolOutputFormat;
import com.minos.output.SymbolResultRenderer;
import com.minos.store.InMemoryCodeKnowledgeStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SymbolQueryServiceTest {

    private static final String PROJECT_ID = "project-1";
    private static final String SYMBOL_ID = "symbol-document-ingestion-service";
    private static final String QUALIFIED_NAME =
            "com.minos.fixture.DocumentIngestionService";

    @Test
    void findSymbolUsesOnlyMinosContracts() {
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore();
        store.putSymbols(List.of(documentIngestionService()));

        SymbolQueryService service = new SymbolQueryService(store);

        List<Symbol> result = service.findSymbol(PROJECT_ID, "DocumentIngestion", 10);

        assertEquals(1, result.size());
        assertEquals(SYMBOL_ID, result.getFirst().id());
        assertEquals(QUALIFIED_NAME, result.getFirst().qualifiedName());
        assertEquals(SymbolIdentityQuality.CANONICAL, result.getFirst().identityQuality());
    }

    @Test
    void findSymbolResultsExposeCompactIdentityLocationAndProvenance() {
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore();
        store.putSymbols(List.of(documentIngestionService()));
        SymbolQueryService service = new SymbolQueryService(store);

        List<SymbolResult> results = service.findSymbolResults(
                PROJECT_ID,
                SymbolSearchCriteria.qualifiedName(QUALIFIED_NAME, 10)
        );

        SymbolResult result = results.getFirst();
        assertEquals(SYMBOL_ID, result.id());
        assertEquals(PROJECT_ID + "|java|CLASS|" + QUALIFIED_NAME, result.symbolKey());
        assertEquals(SymbolIdentityQuality.CANONICAL, result.identityQuality());
        assertEquals(PROJECT_ID, result.projectId());
        assertEquals("module-main", result.moduleId());
        assertEquals("file-service", result.fileId());
        assertEquals(SymbolKind.CLASS, result.kind());
        assertEquals("DocumentIngestionService", result.name());
        assertEquals(QUALIFIED_NAME, result.qualifiedName());
        assertNull(result.signature());
        assertEquals("java", result.language());
        assertEquals(12, result.location().startLine());
        assertEquals(ResolutionStatus.RESOLVED, result.resolutionStatus());
        assertEquals("fixture-provider", result.origin().providerId());
        assertFalse(result.external());
        assertFalse(result.generated());
        assertThrows(UnsupportedOperationException.class, () -> results.add(result));
    }

    @Test
    void queryResultsRenderEndToEndWithoutOpaqueProviderReferences() {
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore();
        store.putSymbols(List.of(documentIngestionService()));
        SymbolQueryService service = new SymbolQueryService(store);

        String json = SymbolResultRenderer.render(
                service.findSymbolResults(PROJECT_ID, "DocumentIngestionService", 10),
                SymbolOutputFormat.JSON
        );

        assertTrue(json.contains("\"count\":1"));
        assertTrue(json.contains("\"qualifiedName\":\"" + QUALIFIED_NAME + "\""));
        assertFalse(json.contains("opaque-provider-symbol"));
    }

    @Test
    void findSymbolRanksExactAndLocalMatchesBeforeBroaderMatches() {
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore();
        store.putSymbols(List.of(
                symbol("symbol-legacy", "LegacyDocumentIngestionService",
                        "com.minos.legacy.LegacyDocumentIngestionService",
                        SymbolKind.CLASS, "module-legacy", "file-legacy", 8, false),
                symbol("symbol-external", "DocumentIngestionService",
                        "org.example.DocumentIngestionService",
                        SymbolKind.CLASS, null, null, 0, true),
                symbol("symbol-factory", "DocumentIngestionServiceFactory",
                        "com.minos.fixture.DocumentIngestionServiceFactory",
                        SymbolKind.CLASS, "module-main", "file-factory", 5, false),
                documentIngestionService()
        ));

        SymbolQueryService service = new SymbolQueryService(store);

        List<String> ids = service.findSymbol(PROJECT_ID, "documentingestionservice", 10)
                .stream()
                .map(Symbol::id)
                .toList();

        assertEquals(List.of(
                SYMBOL_ID,
                "symbol-external",
                "symbol-factory",
                "symbol-legacy"
        ), ids);
        assertEquals(SYMBOL_ID, service.findSymbol(PROJECT_ID, QUALIFIED_NAME, 10).getFirst().id());
    }

    @Test
    void structuredSearchCombinesQualifiedNameKindAndModuleFilters() {
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore();
        store.putSymbols(List.of(
                documentIngestionService(),
                symbol("symbol-method", "DocumentIngestionService", QUALIFIED_NAME,
                        SymbolKind.METHOD, "module-main", "file-service", 20, false),
                symbol("symbol-secondary", "DocumentIngestionService", QUALIFIED_NAME,
                        SymbolKind.CLASS, "module-secondary", "file-secondary", 4, false)
        ));

        SymbolQueryService service = new SymbolQueryService(store);
        SymbolSearchCriteria criteria = new SymbolSearchCriteria(
                "document",
                QUALIFIED_NAME,
                SymbolKind.CLASS,
                "module-main",
                10
        );

        List<SymbolResult> result = service.findSymbolResults(PROJECT_ID, criteria);

        assertEquals(List.of(SYMBOL_ID), result.stream().map(SymbolResult::id).toList());
    }

    @Test
    void getFileSymbolsReturnsDeclarationsInSourceOrder() {
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore();
        store.putSymbols(List.of(
                symbol("symbol-method", "ingest", QUALIFIED_NAME + ".ingest",
                        SymbolKind.METHOD, "module-main", "file-service", 48, false),
                documentIngestionService(),
                symbol("symbol-other-file", "OtherService", "com.minos.fixture.OtherService",
                        SymbolKind.CLASS, "module-main", "file-other", 1, false)
        ));

        SymbolQueryService service = new SymbolQueryService(store);

        List<String> ids = service.getFileSymbolResults(PROJECT_ID, "file-service", 10)
                .stream()
                .map(SymbolResult::id)
                .toList();

        assertEquals(List.of(SYMBOL_ID, "symbol-method"), ids);
    }

    @Test
    void findUsagesExcludesDefinitionsAndUnresolvedOccurrences() {
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore();
        store.putSymbols(List.of(documentIngestionService()));
        store.putOccurrences(List.of(
                resolvedOccurrence("occ-definition", "file-service", 12,
                        Set.of(OccurrenceRole.DEFINITION, OccurrenceRole.TEST)),
                resolvedOccurrence("occ-use-b", "file-resource", 42,
                        Set.of(OccurrenceRole.REFERENCE, OccurrenceRole.READ)),
                resolvedOccurrence("occ-use-a", "file-resource", 18,
                        Set.of(OccurrenceRole.REFERENCE, OccurrenceRole.CALL)),
                unresolvedOccurrence("occ-unresolved", "file-resource", 55)
        ));

        SymbolQueryService service = new SymbolQueryService(store);

        List<SymbolOccurrence> usages = service.findUsages(PROJECT_ID, SYMBOL_ID, 10);

        assertEquals(2, usages.size());
        assertEquals("occ-use-a", usages.get(0).id());
        assertEquals("occ-use-b", usages.get(1).id());
        assertTrue(usages.stream().noneMatch(SymbolOccurrence::isDefinitionOccurrence));
        assertTrue(usages.get(1).roles().containsAll(Set.of(OccurrenceRole.REFERENCE, OccurrenceRole.READ)));
    }

    private static Symbol documentIngestionService() {
        return symbol(
                SYMBOL_ID,
                "DocumentIngestionService",
                QUALIFIED_NAME,
                SymbolKind.CLASS,
                "module-main",
                "file-service",
                12,
                false
        );
    }

    private static Symbol symbol(
            String id,
            String name,
            String qualifiedName,
            SymbolKind kind,
            String moduleId,
            String fileId,
            int line,
            boolean external) {
        return new Symbol(
                id,
                PROJECT_ID + "|java|" + kind + "|" + qualifiedName,
                SymbolIdentityQuality.CANONICAL,
                PROJECT_ID,
                moduleId,
                fileId,
                null,
                kind,
                name,
                qualifiedName,
                null,
                "java",
                fileId == null ? null : new SymbolLocation(
                        fileId, line, 0, line + 1, 1, PositionEncoding.UTF16_CODE_UNITS),
                ResolutionStatus.RESOLVED,
                origin(),
                external,
                false,
                Set.of(new ProviderReference("fixture-provider", "opaque-provider-symbol"))
        );
    }

    private static SymbolOccurrence resolvedOccurrence(
            String id,
            String fileId,
            int line,
            Set<OccurrenceRole> roles) {
        return new SymbolOccurrence(
                id,
                PROJECT_ID,
                new ResolvedSymbolReference(SYMBOL_ID),
                new SymbolLocation(
                        fileId, line, 0, line, 32, PositionEncoding.UTF16_CODE_UNITS),
                roles,
                ResolutionStatus.RESOLVED,
                origin(),
                Set.of()
        );
    }

    private static SymbolOccurrence unresolvedOccurrence(String id, String fileId, int line) {
        return new SymbolOccurrence(
                id,
                PROJECT_ID,
                new UnresolvedSymbolReference(
                        "UnknownType",
                        null,
                        "java",
                        "fixture unresolved reference",
                        Set.of()
                ),
                new SymbolLocation(
                        fileId, line, 0, line, 11, PositionEncoding.UTF16_CODE_UNITS),
                Set.of(OccurrenceRole.REFERENCE),
                ResolutionStatus.UNRESOLVED,
                origin(),
                Set.of()
        );
    }

    private static Origin origin() {
        return new Origin(
                "fixture-provider",
                "TEST_FIXTURE",
                "1",
                "run-1",
                OriginType.OTHER
        );
    }
}
