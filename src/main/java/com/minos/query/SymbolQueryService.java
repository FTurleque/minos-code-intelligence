package com.minos.query;

import com.minos.domain.Symbol;
import com.minos.domain.SymbolOccurrence;
import com.minos.domain.SymbolSearchCriteria;
import com.minos.store.CodeKnowledgeStore;

import java.util.List;
import java.util.Objects;

/**
 * Cas d'usage MINOS pour rechercher les symboles et leurs occurrences.
 */
public final class SymbolQueryService {

    private final CodeKnowledgeStore knowledgeStore;

    public SymbolQueryService(CodeKnowledgeStore knowledgeStore) {
        this.knowledgeStore = Objects.requireNonNull(knowledgeStore, "knowledgeStore");
    }

    public List<Symbol> findSymbol(String projectId, String query, int limit) {
        return knowledgeStore.findSymbols(projectId, query, limit);
    }

    public List<SymbolResult> findSymbolResults(String projectId, String query, int limit) {
        return toResults(findSymbol(projectId, query, limit));
    }

    public List<Symbol> findSymbols(String projectId, SymbolSearchCriteria criteria) {
        return knowledgeStore.findSymbols(projectId, Objects.requireNonNull(criteria, "criteria"));
    }

    public List<SymbolResult> findSymbolResults(String projectId, SymbolSearchCriteria criteria) {
        return toResults(findSymbols(projectId, criteria));
    }

    public List<Symbol> getFileSymbols(String projectId, String fileId, int limit) {
        return knowledgeStore.findFileSymbols(projectId, fileId, limit);
    }

    public List<SymbolResult> getFileSymbolResults(String projectId, String fileId, int limit) {
        return toResults(getFileSymbols(projectId, fileId, limit));
    }

    public List<SymbolOccurrence> findUsages(String projectId, String symbolId, int limit) {
        return knowledgeStore.findUsages(projectId, symbolId, limit);
    }

    public List<UsageResult> findUsageResults(String projectId, String symbolId, int limit) {
        return findUsages(projectId, symbolId, limit).stream()
                .map(UsageResult::from)
                .toList();
    }

    private static List<SymbolResult> toResults(List<Symbol> symbols) {
        return symbols.stream().map(SymbolResult::from).toList();
    }
}
