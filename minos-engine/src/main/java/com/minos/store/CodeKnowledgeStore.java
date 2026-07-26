package com.minos.store;

import com.minos.domain.Relationship;
import com.minos.domain.RelationshipSearchCriteria;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolOccurrence;
import com.minos.domain.SymbolSearchCriteria;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Port MINOS pour stocker et interroger la connaissance normalisée du code.
 *
 * <p>Ce contrat est volontairement défini par les besoins MINOS et ne reflète
 * aucune API SCIP, Glean ou d'un backend particulier.</p>
 */
public interface CodeKnowledgeStore {

    void putSymbols(Collection<Symbol> symbols);

    void putOccurrences(Collection<SymbolOccurrence> occurrences);

    void putRelationships(Collection<Relationship> relationships);

    Optional<Symbol> findSymbolById(String projectId, String symbolId);

    List<Symbol> findSymbols(String projectId, SymbolSearchCriteria criteria);

    default List<Symbol> findSymbols(String projectId, String query, int limit) {
        return findSymbols(projectId, SymbolSearchCriteria.lexical(query, limit));
    }

    List<Symbol> findFileSymbols(String projectId, String fileId, int limit);

    List<SymbolOccurrence> findUsages(String projectId, String symbolId, int limit);

    List<Relationship> findRelationships(String projectId, RelationshipSearchCriteria criteria);
}
