package io.github.fturleque.minos.query;

import io.github.fturleque.minos.domain.OccurrenceRole;
import io.github.fturleque.minos.domain.Origin;
import io.github.fturleque.minos.domain.OriginType;
import io.github.fturleque.minos.domain.ResolutionStatus;
import io.github.fturleque.minos.domain.Symbol;
import io.github.fturleque.minos.domain.SymbolKind;
import io.github.fturleque.minos.domain.SymbolLocation;
import io.github.fturleque.minos.domain.SymbolOccurrence;
import io.github.fturleque.minos.store.InMemoryCodeKnowledgeStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SymbolQueryServiceTest {

    private static final String PROJECT_ID = "project-1";
    private static final String SYMBOL_ID = "symbol-document-ingestion-service";

    @Test
    void findSymbolUsesOnlyMinosContracts() {
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore();
        store.putSymbols(List.of(documentIngestionService()));

        SymbolQueryService service = new SymbolQueryService(store);

        List<Symbol> result = service.findSymbol(PROJECT_ID, "DocumentIngestion", 10);

        assertEquals(1, result.size());
        assertEquals(SYMBOL_ID, result.getFirst().id());
        assertEquals("fr.ariane.document.DocumentIngestionService", result.getFirst().qualifiedName());
    }

    @Test
    void findUsagesExcludesDefinitionAndReturnsDeterministicOrder() {
        InMemoryCodeKnowledgeStore store = new InMemoryCodeKnowledgeStore();
        store.putSymbols(List.of(documentIngestionService()));
        store.putOccurrences(List.of(
                occurrence("occ-definition", "file-service", 12, OccurrenceRole.DEFINITION),
                occurrence("occ-use-b", "file-resource", 42, OccurrenceRole.REFERENCE),
                occurrence("occ-use-a", "file-resource", 18, OccurrenceRole.CALL)
        ));

        SymbolQueryService service = new SymbolQueryService(store);

        List<SymbolOccurrence> usages = service.findUsages(PROJECT_ID, SYMBOL_ID, 10);

        assertEquals(2, usages.size());
        assertEquals("occ-use-a", usages.get(0).id());
        assertEquals("occ-use-b", usages.get(1).id());
        assertTrue(usages.stream().noneMatch(usage -> usage.role() == OccurrenceRole.DEFINITION));
    }

    private static Symbol documentIngestionService() {
        return new Symbol(
                SYMBOL_ID,
                "project-1|java|CLASS|fr.ariane.document.DocumentIngestionService",
                PROJECT_ID,
                "module-main",
                "file-service",
                null,
                SymbolKind.CLASS,
                "DocumentIngestionService",
                "fr.ariane.document.DocumentIngestionService",
                null,
                "java",
                new SymbolLocation("file-service", 12, 0, 120, 1),
                ResolutionStatus.RESOLVED,
                origin(),
                false,
                false
        );
    }

    private static SymbolOccurrence occurrence(
            String id,
            String fileId,
            int line,
            OccurrenceRole role) {
        return new SymbolOccurrence(
                id,
                PROJECT_ID,
                SYMBOL_ID,
                new SymbolLocation(fileId, line, 0, line, 32),
                role,
                ResolutionStatus.RESOLVED,
                origin()
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
