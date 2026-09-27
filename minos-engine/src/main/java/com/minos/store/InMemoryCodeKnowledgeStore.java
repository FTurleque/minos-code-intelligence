package com.minos.store;

import com.minos.domain.CodeEntityRef;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipDirection;
import com.minos.domain.RelationshipSearchCriteria;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolOccurrence;
import com.minos.domain.SymbolSearchCriteria;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic in-memory MINOS store with reconstructible secondary query indexes.
 *
 * <p>The normalized entities remain the source of truth. Indexes are rebuilt from those
 * entities after mutations and are immutable between writes, so query readers never observe
 * partially updated index state.</p>
 */
public final class InMemoryCodeKnowledgeStore implements CodeKnowledgeStore {

    private final Map<String, Symbol> symbolsByScopedId = new ConcurrentHashMap<>();
    private final Map<String, SymbolOccurrence> occurrencesByScopedId = new ConcurrentHashMap<>();
    private final Map<String, Relationship> relationshipsByScopedId = new ConcurrentHashMap<>();

    private volatile SymbolIndexes symbolIndexes = SymbolIndexes.empty();
    private volatile OccurrenceIndexes occurrenceIndexes = OccurrenceIndexes.empty();
    private volatile RelationshipIndexes relationshipIndexes = RelationshipIndexes.empty();

    public InMemoryCodeKnowledgeStore() {
    }

    /** Builds one complete indexed view from an immutable persisted snapshot. */
    public InMemoryCodeKnowledgeStore(CodeKnowledgeSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        snapshot.symbols().forEach(symbol ->
                symbolsByScopedId.put(scopedKey(symbol.projectId(), symbol.id()), symbol));
        snapshot.occurrences().forEach(occurrence ->
                occurrencesByScopedId.put(scopedKey(occurrence.projectId(), occurrence.id()), occurrence));
        snapshot.relationships().forEach(relationship ->
                relationshipsByScopedId.put(scopedKey(relationship.projectId(), relationship.id()), relationship));
        rebuildAllIndexes();
    }

    @Override
    public synchronized void putSymbols(Collection<Symbol> symbols) {
        if (symbols == null) {
            return;
        }
        symbols.forEach(symbol -> symbolsByScopedId.put(
                scopedKey(symbol.projectId(), symbol.id()),
                symbol
        ));
        symbolIndexes = SymbolIndexes.build(symbolsByScopedId.values());
    }

    @Override
    public synchronized void putOccurrences(Collection<SymbolOccurrence> occurrences) {
        if (occurrences == null) {
            return;
        }
        occurrences.forEach(occurrence -> occurrencesByScopedId.put(
                scopedKey(occurrence.projectId(), occurrence.id()),
                occurrence
        ));
        occurrenceIndexes = OccurrenceIndexes.build(occurrencesByScopedId.values());
    }

    @Override
    public synchronized void putRelationships(Collection<Relationship> relationships) {
        if (relationships == null) {
            return;
        }
        relationships.forEach(relationship -> relationshipsByScopedId.put(
                scopedKey(relationship.projectId(), relationship.id()),
                relationship
        ));
        relationshipIndexes = RelationshipIndexes.build(relationshipsByScopedId.values());
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
        List<Symbol> candidates = symbolCandidates(projectId, criteria, normalizedQuery);

        return candidates.stream()
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

        return symbolIndexes.byFileId()
                .getOrDefault(scopedKey(projectId, fileId), List.of())
                .stream()
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

        return occurrenceIndexes.byResolvedSymbolId()
                .getOrDefault(scopedKey(projectId, symbolId), List.of())
                .stream()
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

        return relationshipCandidates(projectId, criteria).stream()
                .filter(relationship -> projectId.equals(relationship.projectId()))
                .filter(relationship -> matchesDirection(relationship, criteria))
                .filter(relationship -> criteria.kinds().isEmpty()
                        || criteria.kinds().contains(relationship.kind()))
                .filter(relationship -> criteria.resolutionStatus() == null
                        || criteria.resolutionStatus() == relationship.resolutionStatus())
                .filter(relationship -> criteria.nature() == null
                        || criteria.nature() == relationship.nature())
                .sorted(Comparator
                        .comparingInt((Relationship relationship) -> directionRank(relationship, criteria))
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
                        .thenComparing(Relationship::unresolvedTarget, Comparator.nullsLast(String::compareTo))
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

    /** Measurements used by M15/M16 to make index cost explicit. */
    public IndexMetrics indexMetrics() {
        SymbolIndexes symbols = symbolIndexes;
        OccurrenceIndexes occurrences = occurrenceIndexes;
        RelationshipIndexes relationships = relationshipIndexes;
        long references = listSize(symbols.byProject())
                + listSize(symbols.byNormalizedName())
                + listSize(symbols.byQualifiedName())
                + listSize(symbols.byNormalizedQualifiedName())
                + listSize(symbols.byFileId())
                + listSize(occurrences.byResolvedSymbolId())
                + listSize(relationships.bySourceEntity())
                + listSize(relationships.byTargetEntity())
                + listSize(relationships.byKind());
        return new IndexMetrics(
                symbolsByScopedId.size(),
                symbols.byNormalizedName().size(),
                symbols.byQualifiedName().size(),
                symbols.byFileId().size(),
                occurrences.byResolvedSymbolId().size(),
                relationships.bySourceEntity().size(),
                relationships.byTargetEntity().size(),
                relationships.byKind().size(),
                references
        );
    }

    private synchronized void rebuildAllIndexes() {
        symbolIndexes = SymbolIndexes.build(symbolsByScopedId.values());
        occurrenceIndexes = OccurrenceIndexes.build(occurrencesByScopedId.values());
        relationshipIndexes = RelationshipIndexes.build(relationshipsByScopedId.values());
    }

    private List<Symbol> symbolCandidates(
            String projectId,
            SymbolSearchCriteria criteria,
            String normalizedQuery
    ) {
        SymbolIndexes indexes = symbolIndexes;
        if (criteria.qualifiedName() != null) {
            return indexes.byQualifiedName().getOrDefault(
                    scopedKey(projectId, criteria.qualifiedName()),
                    List.of()
            );
        }
        if (normalizedQuery != null && criteria.kind() == null && criteria.moduleId() == null) {
            List<Symbol> exact = mergeDistinctSymbols(
                    indexes.byNormalizedName().getOrDefault(scopedKey(projectId, normalizedQuery), List.of()),
                    indexes.byNormalizedQualifiedName().getOrDefault(
                            scopedKey(projectId, normalizedQuery), List.of())
            );
            // Exact-name matches rank before prefix/contains matches. If they already satisfy
            // the requested limit, no project-wide lexical scan is necessary.
            if (exact.size() >= criteria.limit()) {
                return exact;
            }
        }
        return indexes.byProject().getOrDefault(projectId, List.of());
    }

    private List<Relationship> relationshipCandidates(
            String projectId,
            RelationshipSearchCriteria criteria
    ) {
        RelationshipIndexes indexes = relationshipIndexes;
        String anchorKey = entityKey(projectId, criteria.anchor());
        return switch (criteria.direction()) {
            case OUTGOING -> indexes.bySourceEntity().getOrDefault(anchorKey, List.of());
            case INCOMING -> indexes.byTargetEntity().getOrDefault(anchorKey, List.of());
            case ANY -> mergeDistinctRelationships(
                    indexes.bySourceEntity().getOrDefault(anchorKey, List.of()),
                    indexes.byTargetEntity().getOrDefault(anchorKey, List.of())
            );
        };
    }

    private static List<Symbol> mergeDistinctSymbols(List<Symbol> first, List<Symbol> second) {
        LinkedHashMap<String, Symbol> values = new LinkedHashMap<>();
        first.forEach(symbol -> values.put(scopedKey(symbol.projectId(), symbol.id()), symbol));
        second.forEach(symbol -> values.put(scopedKey(symbol.projectId(), symbol.id()), symbol));
        return List.copyOf(values.values());
    }

    private static List<Relationship> mergeDistinctRelationships(
            List<Relationship> first,
            List<Relationship> second
    ) {
        LinkedHashMap<String, Relationship> values = new LinkedHashMap<>();
        first.forEach(value -> values.put(scopedKey(value.projectId(), value.id()), value));
        second.forEach(value -> values.put(scopedKey(value.projectId(), value.id()), value));
        return List.copyOf(values.values());
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

    private static int startLine(Symbol symbol) {
        return symbol.location() == null ? Integer.MAX_VALUE : symbol.location().startLine();
    }

    private static int startColumn(Symbol symbol) {
        return symbol.location() == null ? Integer.MAX_VALUE : symbol.location().startColumn();
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

    private static String entityKey(String projectId, CodeEntityRef entity) {
        return projectId + "\u0000" + entity.type().name() + "\u0000" + entity.id();
    }

    private static <T> Map<String, List<T>> freeze(Map<String, List<T>> source) {
        Map<String, List<T>> frozen = new LinkedHashMap<>();
        source.forEach((key, value) -> frozen.put(key, List.copyOf(value)));
        return Map.copyOf(frozen);
    }

    private static <T> void add(Map<String, List<T>> index, String key, T value) {
        index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
    }

    private static long listSize(Map<String, ? extends List<?>> index) {
        return index.values().stream().mapToLong(List::size).sum();
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

    public record IndexMetrics(
            int symbolIdEntries,
            int normalizedNameKeys,
            int qualifiedNameKeys,
            int fileIdKeys,
            int resolvedSymbolIdKeys,
            int sourceEntityKeys,
            int targetEntityKeys,
            int relationshipKindKeys,
            long indexReferences
    ) {
    }

    private record SymbolIndexes(
            Map<String, List<Symbol>> byProject,
            Map<String, List<Symbol>> byNormalizedName,
            Map<String, List<Symbol>> byQualifiedName,
            Map<String, List<Symbol>> byNormalizedQualifiedName,
            Map<String, List<Symbol>> byFileId
    ) {
        static SymbolIndexes empty() {
            return new SymbolIndexes(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }

        static SymbolIndexes build(Collection<Symbol> symbols) {
            Map<String, List<Symbol>> project = new LinkedHashMap<>();
            Map<String, List<Symbol>> name = new LinkedHashMap<>();
            Map<String, List<Symbol>> qualified = new LinkedHashMap<>();
            Map<String, List<Symbol>> normalizedQualified = new LinkedHashMap<>();
            Map<String, List<Symbol>> file = new LinkedHashMap<>();
            for (Symbol symbol : symbols) {
                add(project, symbol.projectId(), symbol);
                add(name, scopedKey(symbol.projectId(), normalize(symbol.name())), symbol);
                if (symbol.qualifiedName() != null) {
                    add(qualified, scopedKey(symbol.projectId(), symbol.qualifiedName()), symbol);
                    add(normalizedQualified,
                            scopedKey(symbol.projectId(), normalize(symbol.qualifiedName())), symbol);
                }
                if (symbol.fileId() != null) {
                    add(file, scopedKey(symbol.projectId(), symbol.fileId()), symbol);
                }
                if (symbol.location() != null && !Objects.equals(symbol.fileId(), symbol.location().fileId())) {
                    add(file, scopedKey(symbol.projectId(), symbol.location().fileId()), symbol);
                }
            }
            return new SymbolIndexes(
                    freeze(project),
                    freeze(name),
                    freeze(qualified),
                    freeze(normalizedQualified),
                    freeze(file)
            );
        }
    }

    private record OccurrenceIndexes(Map<String, List<SymbolOccurrence>> byResolvedSymbolId) {
        static OccurrenceIndexes empty() {
            return new OccurrenceIndexes(Map.of());
        }

        static OccurrenceIndexes build(Collection<SymbolOccurrence> occurrences) {
            Map<String, List<SymbolOccurrence>> resolved = new LinkedHashMap<>();
            for (SymbolOccurrence occurrence : occurrences) {
                occurrence.resolvedSymbolId().ifPresent(symbolId ->
                        add(resolved, scopedKey(occurrence.projectId(), symbolId), occurrence));
            }
            return new OccurrenceIndexes(freeze(resolved));
        }
    }

    private record RelationshipIndexes(
            Map<String, List<Relationship>> bySourceEntity,
            Map<String, List<Relationship>> byTargetEntity,
            Map<String, List<Relationship>> byKind
    ) {
        static RelationshipIndexes empty() {
            return new RelationshipIndexes(Map.of(), Map.of(), Map.of());
        }

        static RelationshipIndexes build(Collection<Relationship> relationships) {
            Map<String, List<Relationship>> source = new LinkedHashMap<>();
            Map<String, List<Relationship>> target = new LinkedHashMap<>();
            Map<String, List<Relationship>> kind = new LinkedHashMap<>();
            for (Relationship relationship : relationships) {
                add(source, entityKey(relationship.projectId(), relationship.source()), relationship);
                if (relationship.target() != null) {
                    add(target, entityKey(relationship.projectId(), relationship.target()), relationship);
                }
                add(kind, scopedKey(relationship.projectId(), relationship.kind().name()), relationship);
            }
            return new RelationshipIndexes(freeze(source), freeze(target), freeze(kind));
        }
    }
}
