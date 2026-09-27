package com.minos.orchestration;

import java.util.List;

/**
 * Port : catalogue des extensions de provider d'indexation qualifiées pour cette installation.
 *
 * <p>La couche application le consulte sans connaître l'adaptateur qui le fournit (aujourd'hui le
 * catalogue SCIP). Chaque appel rend la liste courante ; une implémentation ne met rien en cache
 * pour le compte de ses appelants.</p>
 */
@FunctionalInterface
public interface IndexerProviderCatalog {

    List<? extends IndexerProvider> providers();
}
