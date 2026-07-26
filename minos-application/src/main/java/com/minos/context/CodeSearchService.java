package com.minos.context;

import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipDirection;
import com.minos.domain.RelationshipSearchCriteria;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolOccurrence;
import com.minos.domain.SymbolSearchCriteria;
import com.minos.query.RelationshipResult;
import com.minos.query.SymbolResult;
import com.minos.query.UsageResult;
import com.minos.store.CodeKnowledgeStore;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Compose un contexte de code structuré en respectant les budgets M4.
 */
public final class CodeSearchService {

    private static final int RESPONSE_OVERHEAD_TOKENS = 24;

    private final CodeKnowledgeStore knowledgeStore;
    private final SourceReader sourceReader;

    public CodeSearchService(CodeKnowledgeStore knowledgeStore, SourceReader sourceReader) {
        this.knowledgeStore = Objects.requireNonNull(knowledgeStore, "knowledgeStore");
        this.sourceReader = Objects.requireNonNull(sourceReader, "sourceReader");
    }

    public CodeSearchResponse search(String projectId, CodeSearchCriteria criteria)
            throws IOException {
        requireText(projectId, "projectId");
        Objects.requireNonNull(criteria, "criteria");

        SymbolSearchCriteria requestedSymbols = criteria.symbols();
        int probeLimit = requestedSymbols.limit() == Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : requestedSymbols.limit() + 1;
        List<Symbol> rootCandidates = knowledgeStore.findSymbols(
                projectId,
                new SymbolSearchCriteria(
                        requestedSymbols.text(),
                        requestedSymbols.qualifiedName(),
                        requestedSymbols.kind(),
                        requestedSymbols.moduleId(),
                        probeLimit
                )
        );
        boolean rootLimitReached = rootCandidates.size() > requestedSymbols.limit();
        List<Symbol> roots = rootCandidates.stream()
                .limit(requestedSymbols.limit())
                .toList();
        List<CodeContextResult> contexts = new ArrayList<>();
        int usedTokens = RESPONSE_OVERHEAD_TOKENS;
        int avoidedTokens = 0;
        boolean responseTruncated = rootLimitReached;

        for (Symbol root : roots) {
            int symbolTokens = estimate(root);
            if (usedTokens + symbolTokens > criteria.maxTokens()) {
                responseTruncated = true;
                break;
            }

            int contextTokens = symbolTokens;
            boolean contextTruncated = false;
            SourceExcerpt source = null;

            Traversal traversal = traverseRelationships(
                    projectId,
                    root,
                    criteria,
                    criteria.maxTokens() - usedTokens - contextTokens
            );
            contextTokens += traversal.estimatedTokens();
            contextTruncated |= traversal.truncated();

            List<UsageResult> usages = new ArrayList<>();
            if (criteria.usagesPerSymbol() > 0) {
                List<SymbolOccurrence> candidates = knowledgeStore.findUsages(
                        projectId,
                        root.id(),
                        criteria.usagesPerSymbol() + 1
                );
                for (SymbolOccurrence candidate : candidates.stream()
                        .limit(criteria.usagesPerSymbol())
                        .toList()) {
                    UsageResult usage = UsageResult.from(candidate);
                    int usageTokens = estimate(usage);
                    if (usedTokens + contextTokens + usageTokens > criteria.maxTokens()) {
                        contextTruncated = true;
                        break;
                    }
                    usages.add(usage);
                    contextTokens += usageTokens;
                }
                if (candidates.size() > criteria.usagesPerSymbol()) {
                    contextTruncated = true;
                }
            }

            if (criteria.includeSource() && root.location() != null) {
                int remaining = criteria.maxTokens() - usedTokens - contextTokens;
                if (remaining > 0) {
                    Optional<SourceExcerpt> excerpt = sourceReader.readExcerpt(
                            root.location(),
                            criteria.contextLines(),
                            remaining
                    );
                    if (excerpt.isPresent()) {
                        source = excerpt.orElseThrow();
                        contextTokens += source.estimatedTokens();
                        avoidedTokens += source.estimatedTokensAvoided();
                        contextTruncated |= source.truncated();
                    }
                } else {
                    contextTruncated = true;
                }
            }

            contexts.add(new CodeContextResult(
                    SymbolResult.from(root),
                    source,
                    traversal.relationships(),
                    usages,
                    contextTokens,
                    contextTruncated
            ));
            usedTokens += contextTokens;
            responseTruncated |= contextTruncated;
            if (usedTokens >= criteria.maxTokens()) {
                responseTruncated |= contexts.size() < roots.size();
                break;
            }
        }

        return new CodeSearchResponse(
                projectId,
                criteria.symbols().text(),
                criteria.maxDepth(),
                criteria.maxTokens(),
                Math.min(usedTokens, criteria.maxTokens()),
                avoidedTokens,
                responseTruncated,
                contexts
        );
    }

    public SourceExcerpt getFullSource(String fileId) throws IOException {
        return sourceReader.readFull(fileId);
    }

    private Traversal traverseRelationships(
            String projectId,
            Symbol root,
            CodeSearchCriteria criteria,
            int tokenBudget
    ) {
        if (criteria.maxDepth() == 0 || criteria.relationshipsPerNode() == 0
                || tokenBudget <= 0) {
            return new Traversal(List.of(), 0,
                    criteria.maxDepth() > 0 && criteria.relationshipsPerNode() > 0);
        }

        CodeEntityRef rootReference = new CodeEntityRef(CodeEntityType.SYMBOL, root.id());
        ArrayDeque<EntityDepth> queue = new ArrayDeque<>();
        Set<CodeEntityRef> visitedEntities = new HashSet<>();
        Set<String> visitedRelationships = new HashSet<>();
        List<ContextRelationshipResult> results = new ArrayList<>();
        queue.add(new EntityDepth(rootReference, 0));
        visitedEntities.add(rootReference);
        int usedTokens = 0;
        boolean truncated = false;

        traversal:
        while (!queue.isEmpty()) {
            EntityDepth current = queue.removeFirst();
            if (current.depth() >= criteria.maxDepth()) {
                continue;
            }
            List<Relationship> relationships = knowledgeStore.findRelationships(
                    projectId,
                    RelationshipSearchCriteria.any(
                            current.entity(),
                            Set.of(),
                            criteria.relationshipsPerNode() + 1
                    )
            );
            if (relationships.size() > criteria.relationshipsPerNode()) {
                truncated = true;
            }
            for (Relationship relationship : relationships.stream()
                    .limit(criteria.relationshipsPerNode())
                    .toList()) {
                if (!visitedRelationships.add(relationship.id())) {
                    continue;
                }
                RelationshipResult compact = RelationshipResult.from(relationship);
                int relationshipTokens = estimate(compact);
                if (usedTokens + relationshipTokens > tokenBudget) {
                    truncated = true;
                    break traversal;
                }
                RelationshipDirection direction = relationship.source().equals(current.entity())
                        ? RelationshipDirection.OUTGOING
                        : RelationshipDirection.INCOMING;
                int relationshipDepth = current.depth() + 1;
                results.add(new ContextRelationshipResult(
                        relationshipDepth,
                        current.entity(),
                        direction,
                        compact
                ));
                usedTokens += relationshipTokens;

                CodeEntityRef neighbor = direction == RelationshipDirection.OUTGOING
                        ? relationship.target()
                        : relationship.source();
                if (neighbor != null && relationshipDepth < criteria.maxDepth()
                        && visitedEntities.add(neighbor)) {
                    queue.addLast(new EntityDepth(neighbor, relationshipDepth));
                }
            }
        }

        return new Traversal(List.copyOf(results), usedTokens, truncated);
    }

    private static int estimate(Symbol symbol) {
        return 32 + estimateStrings(
                symbol.id(), symbol.symbolKey(), symbol.moduleId(), symbol.fileId(),
                symbol.name(), symbol.qualifiedName(), symbol.signature(), symbol.language(),
                symbol.origin().providerId(), symbol.origin().providerType());
    }

    private static int estimate(RelationshipResult relationship) {
        int tokens = 36 + estimateStrings(
                relationship.id(), relationship.source().id(),
                relationship.target() == null ? null : relationship.target().id(),
                relationship.unresolvedTarget(), relationship.kind().name(),
                relationship.nature().name(), relationship.origin().providerId());
        return tokens + relationship.evidence().stream()
                .mapToInt(evidence -> 12 + estimateStrings(
                        evidence.type().name(), evidence.description()))
                .sum();
    }

    private static int estimate(UsageResult usage) {
        return 20 + estimateStrings(
                usage.id(), usage.symbolId(), usage.location().fileId(),
                usage.roles().toString());
    }

    private static int estimateStrings(String... values) {
        int total = 0;
        for (String value : values) {
            total += TokenEstimator.estimate(value);
        }
        return total;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private record EntityDepth(CodeEntityRef entity, int depth) {
    }

    private record Traversal(
            List<ContextRelationshipResult> relationships,
            int estimatedTokens,
            boolean truncated
    ) {
    }
}
