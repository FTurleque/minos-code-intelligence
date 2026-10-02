package com.minos.registry;

import java.io.IOException;
import java.util.Optional;

/**
 * Port : correspondance persistée entre racines de projets hôte et conteneur (plan d'administration
 * Docker). L'assemblage {@code minos-app} n'en connaît que ce contrat ; l'implémentation fichier
 * ({@code ProjectPathMappingStore}, minos-storage-local) est câblée par minos-bootstrap.
 */
public interface ProjectPathMappings {

    Optional<ProjectPathMapping> loadOptional() throws IOException;

    void save(ProjectPathMapping mapping) throws IOException;
}
