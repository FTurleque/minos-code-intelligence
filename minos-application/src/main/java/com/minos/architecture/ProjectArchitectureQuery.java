package com.minos.architecture;

import java.io.IOException;

/**
 * Port de lecture des vues d'architecture d'un projet enregistré.
 */
public interface ProjectArchitectureQuery {

    ArchitectureOverview getArchitectureOverview(String projectIdentifier) throws IOException;

    ArchitectureDependencyGraph getModuleDependencies(String projectIdentifier) throws IOException;

    ArchitectureConcentrationReport getArchitectureConcentration(String projectIdentifier) throws IOException;

    ArchitectureCentralityReport getArchitectureCentrality(String projectIdentifier) throws IOException;

    ArchitectureTechnologyReport getArchitectureTechnologies(String projectIdentifier) throws IOException;

    ArchitectureIntelligenceView getArchitectureIntelligence(String projectIdentifier) throws IOException;

    ArchitectureModuleContext getModuleContext(String projectIdentifier, String moduleIdentifier) throws IOException;

    /**
     * Vue d'architecture du projet et identifiant du module demandé, résolu comme par {@link #getModuleContext} :
     * ce qu'il faut à un graphe filtré sur un module. Un module inconnu ou ambigu échoue comme
     * {@link #getModuleContext}.
     *
     * <p>Par défaut, l'enchaînement des deux lectures (correct, mais deux chargements du contexte du projet) ; une
     * implémentation qui sait charger ce contexte une seule fois redéfinit l'opération.</p>
     */
    default ArchitectureModuleSelection getArchitectureIntelligenceForModule(
            String projectIdentifier, String moduleIdentifier) throws IOException {
        ArchitectureIntelligenceView view = getArchitectureIntelligence(projectIdentifier);
        return new ArchitectureModuleSelection(
                view, getModuleContext(projectIdentifier, moduleIdentifier).module().id());
    }
}