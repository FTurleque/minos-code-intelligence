package io.github.fturleque.minos.query;

import io.github.fturleque.minos.domain.Symbol;
import io.github.fturleque.minos.domain.SymbolOccurrence;
import io.github.fturleque.minos.store.CodeKnowledgeStore;

import java.util.List;
import java.util.Objects;

/**
 * Premiers cas d'usage MINOS nécessaires à la baseline M0.
 */
public final class SymbolQueryService {

    private final CodeKnowledgeStore knowledgeStore;

    public SymbolQueryService(CodeKnowledgeStore knowledgeStore) {
        this.knowledgeStore = Objects.requireNonNull(knowledgeStore, "knowledgeStore");
    }

    public List<Symbol> findSymbol(String projectId, String query, int limit) {
        return knowledgeStore.findSymbols(projectId, query, limit);
    }

    public List<SymbolOccurrence> findUsages(String projectId, String symbolId, int limit) {
        return knowledgeStore.findUsages(projectId, symbolId, limit);
    }
}
