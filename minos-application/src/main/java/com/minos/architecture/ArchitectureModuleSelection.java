package com.minos.architecture;

import java.util.Objects;

import static com.minos.domain.Preconditions.requireText;

/**
 * Vue d'architecture d'un projet et identifiant du module retenu dans cette vue, obtenus par un même chargement
 * du contexte du projet : l'entrée d'un graphe d'architecture filtré sur un module.
 */
public record ArchitectureModuleSelection(ArchitectureIntelligenceView view, String moduleId) {
    public ArchitectureModuleSelection {
        Objects.requireNonNull(view, "view");
        requireText(moduleId, "moduleId");
    }
}
