package com.minos.store;

import com.minos.domain.Relationship;
import com.minos.domain.RelationshipKind;

import java.util.List;
import java.util.Set;

/**
 * Relations qui entrent dans chaque symbole d'un projet, avec les deux faits qu'une traversée d'impact énonce sur
 * les relations qu'elle ne peut pas suivre. Immuable.
 */
public interface IncomingRelationships {

    /** Relations dont la cible est ce symbole, ordonnées par identifiant de source, sorte, puis identifiant. */
    List<Relationship> incoming(String symbolId);

    /** Vrai si une relation de l'une de ces sortes n'a pas de cible résolue. */
    boolean hasUnresolved(Set<RelationshipKind> kinds);

    /**
     * Vrai si une relation de l'une de ces sortes a une cible mais ne relie pas deux symboles du projet : un fichier,
     * un module, ou un symbole que le projet ne contient pas.
     */
    boolean leavesProjectSymbols(Set<RelationshipKind> kinds);

    /** Nombre de symboles dans lesquels entre au moins une relation. */
    int targetCount();

    /** Index ponctuel d'un snapshot, pour un appelant qui ne tient aucune vue ; rien n'est mis en cache. */
    static IncomingRelationships of(CodeKnowledgeSnapshot snapshot) {
        return InMemoryCodeKnowledgeStore.SymbolEdgeIndex.of(snapshot);
    }
}
