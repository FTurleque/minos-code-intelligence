package com.minos.store;

import com.minos.domain.Relationship;
import com.minos.domain.RelationshipDirection;
import com.minos.domain.RelationshipSearchCriteria;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolOccurrence;
import com.minos.domain.SymbolSearchCriteria;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implémentation mémoire déterministe des contrats de stockage MINOS.
 */
public final class InMemoryCodeKnowledgeStore implements CodeKnowledgeStore {

    private final Map<String, Symbol> symbolsByScopedId = new ConcurrentHashMap<>();
    private final Map<String, SymbolOccurrence> occurrencesByScopedId = new ConcurrentHashMap<>();
    private final Map<String, Relationship> relationshipsByScopedId = new ConcurrentHashMap<>();

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
        relationships.forEach(relationship -> relationshipsByScopedId.put(
                scopedKey(relationship.projectId(), relationship.id()),
                relationship
        ));
    }

    @Override
    public Optional<Symbol> findSymbolById(String projectId, String symbolId) {
        validateText(projectId, "projectId");
        validateText(symbolId, "symbolId");
        return Optional.ofNullable(symbolsByScopedId.get(scopedKey(projectId, symbolId)));
    }

    @Override
    public List<Symbol> findSymbols(String projectId, SymbolSearchCriteria criteria) {
        validateText(projectId, "projectId");
        if (criteria == null) {
            throw new IllegalArgumentException("criteria must not be null");
        }

        String normalizedQuery = normalize(criteria.text());

        return symbolsByScopedId.values().stream()
                .filter(symbol -> projectId.equals(symbol.projectId()))
                .filter(symbol -> criteria.kind() == null || criteria.kind() == symbol.kind())
                .filter(symbol -> criteria.moduleId() == null || criteria.moduleId().equals(symbol.moduleId()))
                .filter(symbol -> criteria.qualifiedName() == null
                        || criteria.qualifiedName().equals(symbol.qualifiedName()))
                .filter(symbol -> normalizedQuery == null || matchRank(symbol, normalizedQuery) < Integer.MAX_VALUE)
                .sorted(Comparator
                        .comparingInt((Symbol symbol) -> matchRank(symbol, normalizedQuery))
                        .thenComparing(Symbol::external)
                        .thenComparing(Symbol::generated)
                        .thenComparing(Symbol::qualifiedName, Comparator.nullsLast(String::compareTo))
                        .thenComparing(Symbol::name)
                        .thenComparing(Symbol::signature, Comparator.nullsLast(String::compareTo))
                        .thenComparing(Symbol::id))
                .limit(criteria.limit())
                .toList();
    }

    @Override
    public List<Symbol> findFileSymbols(String projectId, String fileId, int limit) {
        validateText(projectId, "projectId");
        validateText(fileId, "fileId");
        validateLimit(limit);

        return symbolsByScopedId.values().stream()
                .filter(symbol -> projectId.equals(symbol.projectId()))
                .filter(symbol -> declaredInFile(symbol, fileId))
                .sorted(Comparator
                        .comparingInt(InMemoryCodeKnowledgeStore::startLine)
                        .thenComparingInt(InMemoryCodeKnowledgeStore::startColumn)
                        .thenComparing(Symbol::qualifiedName, Comparator.nullsLast(String::compareTo))
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

    @Override
    public List<Relationship> findRelationships(
            String projectId,
            RelationshipSearchCriteria criteria
    ) {
        validateText(projectId, "projectId");
        if (criteria == null) {
            throw new IllegalArgumentException("criteria must not be null");
        }

        return relationshipsByScopedId.values().stream()
                .filter(relationship -> projectId.equals(relationship.projectId()))
                .filter(relationship -> matchesDirection(relationship, criteria))
                .filter(relationship -> criteria.kinds().isEmpty()
                        || criteria.kinds().contains(relationship.kind()))
                .filter(relationship -> criteria.resolutionStatus() == null
                        || criteria.resolutionStatus() == relationship.resolutionStatus())
                .filter(relationship -> criteria.nature() == null
                        || criteria.nature() == relationship.nature())
                .sorted(Comparator
                        .comparingInt((Relationship relationship) -> directionRank(
                                relationship,
                                criteria
                        ))
                        .thenComparing(Relationship::kind)
                        .thenComparing(relationship -> relationship.target() == null)
                        .thenComparing(relationship -> relationship.source().type())
                        .thenComparing(relationship -> relationship.source().id())
                        .thenComparing(
                                relationship -> relationship.target() == null
                                        ? null
                                        : relationship.target().type(),
                                Comparator.nullsLast(Comparator.naturalOrder())
                        )
                        .thenComparing(
                                relationship -> relationship.target() == null
                                        ? null
                                        : relationship.target().id(),
                                Comparator.nullsLast(String::compareTo)
                        )
                        .thenComparing(
                                Relationship::unresolvedTarget,
                                Comparator.nullsLast(String::compareTo)
                        )
                        .thenComparing(
                                relationship -> relationship.location() == null
                                        ? null
                                        : relationship.location().fileId(),
                                Comparator.nullsLast(String::compareTo)
                        )
                        .thenComparingInt(InMemoryCodeKnowledgeStore::relationshipStartLine)
                        .thenComparingInt(InMemoryCodeKnowledgeStore::relationshipStartColumn)
                        .thenComparing(Relationship::id))
                .limit(criteria.limit())
                .toList();
    }

    private static int matchRank(Symbol symbol, String normalizedQuery) {
        if (normalizedQuery == null) {
            return 0;
        }
        if (equalsIgnoreCase(symbol.qualifiedName(), normalizedQuery)) {
            return 0;
        }
        if (equalsIgnoreCase(symbol.name(), normalizedQuery)) {
            return 1;
        }
        if (startsWithIgnoreCase(symbol.name(), normalizedQuery)) {
            return 2;
        }
        if (startsWithIgnoreCase(symbol.qualifiedName(), normalizedQuery)) {
            return 3;
        }
        if (containsIgnoreCase(symbol.name(), normalizedQuery)) {
            return 4;
        }
        if (containsIgnoreCase(symbol.qualifiedName(), normalizedQuery)) {
            return 5;
        }
        if (containsIgnoreCase(symbol.symbolKey(), normalizedQuery)) {
            return 6;
        }
        return Integer.MAX_VALUE;
    }

    private static boolean declaredInFile(Symbol symbol, String fileId) {
        return fileId.equals(symbol.fileId())
                || symbol.location() != null && fileId.equals(symbol.location().fileId());
    }

    private static int startLine(Symbol symbol) {
        return symbol.location() == null ? Integer.MAX_VALUE : symbol.location().startLine();
    }

    private static int startColumn(Symbol symbol) {
        return symbol.location() == null ? Integer.MAX_VALUE : symbol.location().startColumn();
    }

    private static boolean matchesDirection(
            Relationship relationship,
            RelationshipSearchCriteria criteria
    ) {
        boolean outgoing = relationship.source().equals(criteria.anchor());
        boolean incoming = criteria.anchor().equals(relationship.target());
        return switch (criteria.direction()) {
            case OUTGOING -> outgoing;
            case INCOMING -> incoming;
            case ANY -> outgoing || incoming;
        };
    }

    private static int directionRank(
            Relationship relationship,
            RelationshipSearchCriteria criteria
    ) {
        if (criteria.direction() != RelationshipDirection.ANY) {
            return 0;
        }
        return relationship.source().equals(criteria.anchor()) ? 0 : 1;
    }

    private static int relationshipStartLine(Relationship relationship) {
        return relationship.location() == null
                ? Integer.MAX_VALUE
                : relationship.location().startLine();
    }

    private static int relationshipStartColumn(Relationship relationship) {
        return relationship.location() == null
                ? Integer.MAX_VALUE
                : relationship.location().startColumn();
    }

    private static String normalize(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static boolean equalsIgnoreCase(String value, String normalizedQuery) {
        return value != null && value.toLowerCase(Locale.ROOT).equals(normalizedQuery);
    }

    private static boolean startsWithIgnoreCase(String value, String normalizedQuery) {
        return value != null && value.toLowerCase(Locale.ROOT).startsWith(normalizedQuery);
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
