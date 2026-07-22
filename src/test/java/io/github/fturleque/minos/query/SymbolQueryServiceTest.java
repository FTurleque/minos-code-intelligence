package io.github.fturleque.minos.query;

import io.github.fturleque.minos.domain.OccurrenceRole;
import io.github.fturleque.minos.domain.Origin;
import io.github.fturleque.minos.domain.OriginType;
import io.github.fturleque.minos.domain.PositionEncoding;
import io.github.fturleque.minos.domain.ResolutionStatus;
import io.github.fturleque.minos.domain.ResolvedSymbolReference;
import io.github.fturleque.minos.domain.Symbol;
import io.github.fturleque.minos.domain.SymbolIdentityQuality;
import io.github.fturleque.minos.domain.SymbolKind;
import io.github.fturleque.minos.domain.SymbolLocation;
import io.github.fturleque.minos.domain.SymbolOccurrence;
import io.github.fturleque.minos.domain.UnresolvedSymbolReference;
import io.github.fturleque.minos.store.InMemoryCodeKnowledgeStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SymbolQueryServiceTest {

    private static final String PROJECT_ID = "project-1";
    private static final String SYMBOL_ID = "symbol-document-ingestion-service";
    private static final String QUALIFIED_NAME =
            "io.github.fturleque.minos.fixture.DocumentIngestionService";

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
        return new Symbol(
                SYMBOL_ID,
                "project-1|java|CLASS|" + QUALIFIED_NAME,
                SymbolIdentityQuality.CANONICAL,
                PROJECT_ID,
                "module-main",
                "file-service",
                null,
                SymbolKind.CLASS,
                "DocumentIngestionService",
                QUALIFIED_NAME,
                null,
                "java",
                new SymbolLocation(
                        "file-service", 12, 0, 120, 1, PositionEncoding.UTF16_CODE_UNITS),
                ResolutionStatus.RESOLVED,
                origin(),
                false,
                false,
                Set.of()
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
