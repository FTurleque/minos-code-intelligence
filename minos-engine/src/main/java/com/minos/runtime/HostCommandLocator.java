package com.minos.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Port : localisation et invocation des exécutables de l'hôte (PATH, PATHEXT, lanceurs de scripts
 * Windows). La couche application, les surfaces et l'assemblage n'en connaissent que ce contrat ;
 * l'implémentation de production, câblée par minos-bootstrap, interroge l'hôte réel.
 */
public interface HostCommandLocator {

    /** Exécutable résolu pour {@code command}, absent s'il n'est pas trouvé sur l'hôte. */
    Optional<Path> find(String command);

    /** Ligne de commande qui lance {@code executable} avec ses arguments sur l'hôte courant. */
    List<String> invocation(Path executable, String... arguments);
}
