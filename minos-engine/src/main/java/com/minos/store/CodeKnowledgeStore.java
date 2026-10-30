package com.minos.store;

import com.minos.domain.Relationship;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolOccurrence;

import java.util.Collection;

/**
 * Port MINOS pour stocker et interroger la connaissance normalisée du code : les lectures de
 * {@link CodeKnowledgeReader} et la voie d'écriture de l'ingestion.
 *
 * <p>Ce contrat est volontairement défini par les besoins MINOS et ne reflète
 * aucune API SCIP, Glean ou d'un backend particulier. Un cas d'usage de lecture reçoit
 * {@link CodeKnowledgeReader}, jamais ce port (ADR 0056).</p>
 */
public interface CodeKnowledgeStore extends CodeKnowledgeReader {

    void putSymbols(Collection<Symbol> symbols);

    void putOccurrences(Collection<SymbolOccurrence> occurrences);

    void putRelationships(Collection<Relationship> relationships);
}
