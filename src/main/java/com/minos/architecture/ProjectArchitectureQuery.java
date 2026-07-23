package com.minos.architecture;

import java.io.IOException;

/**
 * Port de lecture de la vue d'architecture d'un projet enregistré.
 */
public interface ProjectArchitectureQuery {

    ArchitectureOverview getArchitectureOverview(String projectIdentifier) throws IOException;
}
