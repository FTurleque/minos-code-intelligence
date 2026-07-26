package com.minos.query;

import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipKind;
import com.minos.domain.RelationshipSearchCriteria;
import com.minos.store.CodeKnowledgeStore;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Cas d'usage MINOS pour interroger les relations entrantes et sortantes.
 */
public final class RelationshipQueryService {

    private final CodeKnowledgeStore knowledgeStore;

    public RelationshipQueryService(CodeKnowledgeStore knowledgeStore) {
        this.knowledgeStore = Objects.requireNonNull(knowledgeStore, "knowledgeStore");
    }

    public List<Relationship> findRelationships(
            String projectId,
            RelationshipSearchCriteria criteria
    ) {
        return knowledgeStore.findRelationships(
                projectId,
                Objects.requireNonNull(criteria, "criteria")
        );
    }

    public List<RelationshipResult> findRelationshipResults(
            String projectId,
            RelationshipSearchCriteria criteria
    ) {
        return findRelationships(projectId, criteria).stream()
                .map(RelationshipResult::from)
                .toList();
    }

    public List<RelationshipResult> findImplementations(
            String projectId,
            String symbolId,
            int limit
    ) {
        return findRelationshipResults(
                projectId,
                RelationshipSearchCriteria.incoming(
                        symbol(symbolId),
                        Set.of(RelationshipKind.IMPLEMENTS),
                        limit
                )
        );
    }

    public List<RelationshipResult> findCallers(
            String projectId,
            String symbolId,
            int limit
    ) {
        return findIncoming(projectId, symbol(symbolId), Set.of(RelationshipKind.CALLS), limit);
    }

    public List<RelationshipResult> findCallees(
            String projectId,
            String symbolId,
            int limit
    ) {
        return findOutgoing(projectId, symbol(symbolId), Set.of(RelationshipKind.CALLS), limit);
    }

    public List<RelationshipResult> findDependencies(
            String projectId,
            CodeEntityRef entity,
            int limit
    ) {
        return findOutgoing(projectId, entity, Set.of(RelationshipKind.DEPENDS_ON), limit);
    }

    public List<RelationshipResult> findDependents(
            String projectId,
            CodeEntityRef entity,
            int limit
    ) {
        return findIncoming(projectId, entity, Set.of(RelationshipKind.DEPENDS_ON), limit);
    }

    /**
     * Retourne les relations entrantes test -&gt; symbole de production.
     */
    public List<RelationshipResult> findRelatedTests(
            String projectId,
            String productionSymbolId,
            int limit
    ) {
        return findIncoming(
                projectId,
                symbol(productionSymbolId),
                Set.of(RelationshipKind.RELATED_TEST),
                limit
        );
    }

    public List<RelationshipResult> findOutgoing(
            String projectId,
            CodeEntityRef source,
            Set<RelationshipKind> kinds,
            int limit
    ) {
        return findRelationshipResults(
                projectId,
                RelationshipSearchCriteria.outgoing(source, kinds, limit)
        );
    }

    public List<RelationshipResult> findIncoming(
            String projectId,
            CodeEntityRef target,
            Set<RelationshipKind> kinds,
            int limit
    ) {
        return findRelationshipResults(
                projectId,
                RelationshipSearchCriteria.incoming(target, kinds, limit)
        );
    }

    private static CodeEntityRef symbol(String symbolId) {
        return new CodeEntityRef(CodeEntityType.SYMBOL, symbolId);
    }
}
