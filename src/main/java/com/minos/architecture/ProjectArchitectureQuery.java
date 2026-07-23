package com.minos.architecture;

import java.io.IOException;

/**
 * Port de lecture des vues d'architecture d'un projet enregistré.
 */
public interface ProjectArchitectureQuery {

    ArchitectureOverview getArchitectureOverview(String projectIdentifier) throws IOException;

    ArchitectureDependencyGraph getModuleDependencies(String projectIdentifier) throws IOException;

    ArchitectureConcentrationReport getArchitectureConcentration(String projectIdentifier) throws IOException;
}
