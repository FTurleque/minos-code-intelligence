package com.minos.store;

import com.minos.domain.Relationship;
import com.minos.domain.RelationshipSearchCriteria;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolOccurrence;
import com.minos.domain.SymbolSearchCriteria;

import java.util.List;
import java.util.Optional;

/**
 * Port de lecture de la connaissance normalisée du code (ADR 0056) : les seules lectures appelées par les cas
 * d'usage de requête, sans aucune écriture. {@link CodeKnowledgeStore} l'étend et y ajoute la voie d'écriture de
 * l'ingestion ; l'index mémoire en est une implémentation parmi d'autres.
 */
public interface CodeKnowledgeReader {

    Optional<Symbol> findSymbolById(String projectId, String symbolId);

    List<Symbol> findSymbols(String projectId, SymbolSearchCriteria criteria);

    /**
     * Vrai quand le projet a des symboles et qu'aucun ne porte de module : un filtre par module ne
     * peut alors rien désigner (MINOS-AUD-F04). Un lecteur qui ne le sait pas répond faux.
     */
    default boolean lacksModuleAttribution(String projectId) {
        return false;
    }

    default List<Symbol> findSymbols(String projectId, String query, int limit) {
        return findSymbols(projectId, SymbolSearchCriteria.lexical(query, limit));
    }

    List<Symbol> findFileSymbols(String projectId, String fileId, int limit);

    List<SymbolOccurrence> findUsages(String projectId, String symbolId, int limit);

    List<Relationship> findRelationships(String projectId, RelationshipSearchCriteria criteria);
}
