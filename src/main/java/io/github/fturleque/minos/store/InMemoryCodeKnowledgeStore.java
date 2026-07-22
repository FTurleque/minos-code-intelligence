package io.github.fturleque.minos.store;

import io.github.fturleque.minos.domain.Relationship;
import io.github.fturleque.minos.domain.Symbol;
import io.github.fturleque.minos.domain.SymbolOccurrence;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implémentation mémoire déterministe destinée aux tests et à la baseline M0.
 */
public final class InMemoryCodeKnowledgeStore implements CodeKnowledgeStore {

    private final Map<String, Symbol> symbolsByScopedId = new ConcurrentHashMap<>();
    private final Map<String, SymbolOccurrence> occurrencesByScopedId = new ConcurrentHashMap<>();
    private final Map<String, Relationship> relationshipsById = new ConcurrentHashMap<>();

    @Override
    public void putSymbols(Collection<Symbol> symbols) {
        if (symbols == null) {
            return;
        }
        symbols.forEach(symbol -> symbolsByScopedId.put(scopedKey(symbol.projectId(), symbol.id()), symbol));
    }

    @Override
    public void putOccurrences(Collection<SymbolOccurrence> occurrences) {
        if (occurrences == null) {
            return;
        }
        occurrences.forEach(occurrence ->
                occurrencesByScopedId.put(scopedKey(occurrence.projectId(), occurrence.id()), occurrence));
    }

    @Override
    public void putRelationships(Collection<Relationship> relationships) {
        if (relationships == null) {
            return;
        }
        relationships.forEach(relationship -> relationshipsById.put(relationship.id(), relationship));
    }

    @Override
    public Optional<Symbol> findSymbolById(String projectId, String symbolId) {
        validateText(projectId, "projectId");
        validateText(symbolId, "symbolId");
        return Optional.ofNullable(symbolsByScopedId.get(scopedKey(projectId, symbolId)));
    }

    @Override
    public List<Symbol> findSymbols(String projectId, String query, int limit) {
        validateText(projectId, "projectId");
        validateText(query, "query");
        validateLimit(limit);

        String normalizedQuery = query.toLowerCase(Locale.ROOT);

        return symbolsByScopedId.values().stream()
                .filter(symbol -> projectId.equals(symbol.projectId()))
                .filter(symbol -> matches(symbol, normalizedQuery))
                .sorted(Comparator
                        .comparing(Symbol::qualifiedName, Comparator.nullsLast(String::compareTo))
                        .thenComparing(Symbol::name)
                        .thenComparing(Symbol::id))
                .limit(limit)
                .toList();
    }

    @Override
    public List<SymbolOccurrence> findUsages(String projectId, String symbolId, int limit) {
        validateText(projectId, "projectId");
        validateText(symbolId, "symbolId");
        validateLimit(limit);

        return occurrencesByScopedId.values().stream()
                .filter(occurrence -> projectId.equals(occurrence.projectId()))
                .filter(SymbolOccurrence::isResolved)
                .filter(occurrence -> occurrence.resolvedSymbolId()
                        .map(symbolId::equals)
                        .orElse(false))
                .filter(occurrence -> !occurrence.isDefinitionOccurrence())
                .sorted(Comparator
                        .comparing((SymbolOccurrence occurrence) -> occurrence.location().fileId())
                        .thenComparingInt(occurrence -> occurrence.location().startLine())
                        .thenComparingInt(occurrence -> occurrence.location().startColumn())
                        .thenComparing(SymbolOccurrence::id))
                .limit(limit)
                .toList();
    }

    private static boolean matches(Symbol symbol, String normalizedQuery) {
        return containsIgnoreCase(symbol.name(), normalizedQuery)
                || containsIgnoreCase(symbol.qualifiedName(), normalizedQuery)
                || containsIgnoreCase(symbol.symbolKey(), normalizedQuery);
    }

    private static boolean containsIgnoreCase(String value, String normalizedQuery) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedQuery);
    }

    private static String scopedKey(String projectId, String id) {
        return projectId + "\u0000" + id;
    }

    private static void validateText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    private static void validateLimit(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be greater than zero");
        }
    }
}
