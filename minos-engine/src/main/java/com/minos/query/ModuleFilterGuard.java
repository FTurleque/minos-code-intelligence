package com.minos.query;

import com.minos.domain.SymbolSearchCriteria;
import com.minos.store.CodeKnowledgeStore;

import java.util.Objects;

/**
 * Refuse un filtre par module que le snapshot ne peut pas satisfaire (MINOS-AUD-F04).
 *
 * <p>Un snapshot dont aucun symbole ne porte de module (indexation autonome, ou snapshot persisté avant
 * l'attribution des modules) répondrait « aucun résultat » à tout filtre par module : une réponse vide
 * indiscernable d'un module inconnu. Le refus est explicite et sans chemin, et ne dépend d'aucun mécanisme
 * d'attribution : il vaut aussi pour les snapshots déjà persistés.</p>
 */
public final class ModuleFilterGuard {

    static final String REFUSAL = "module filter is unavailable on this snapshot: none of its symbols records a module; "
            + "reindex the project or search without a module";

    private ModuleFilterGuard() {
    }

    public static void require(CodeKnowledgeStore store, String projectId, SymbolSearchCriteria criteria) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(criteria, "criteria");
        if (criteria.moduleId() != null && store.lacksModuleAttribution(projectId)) {
            throw new IllegalArgumentException(REFUSAL);
        }
    }
}
