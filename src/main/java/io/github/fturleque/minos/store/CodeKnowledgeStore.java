package io.github.fturleque.minos.store;

import io.github.fturleque.minos.domain.Relationship;
import io.github.fturleque.minos.domain.Symbol;
import io.github.fturleque.minos.domain.SymbolOccurrence;

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

    List<Symbol> findSymbols(String projectId, String query, int limit);

    List<SymbolOccurrence> findUsages(String projectId, String symbolId, int limit);
}
