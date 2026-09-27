package com.minos.application;

import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

/**
 * Découverte de la racine de composition (ADR 0042), déterministe et en échec rapide.
 *
 * <p>Exactement une implémentation de {@link MinosApplicationComposer} doit être enregistrée
 * ({@code META-INF/services}, module {@code minos-bootstrap}). Aucune : échec explicite qui nomme le
 * module manquant. Plusieurs : refus, jamais un choix arbitraire. Les messages ne citent que des noms
 * de classes, jamais de chemin. Aucune mise en cache : chaque ouverture relit le classpath courant.</p>
 */
public final class MinosApplicationComposers {

    static final String MISSING = "MINOS composition root is missing: the minos-bootstrap module must be on the"
            + " classpath (no " + MinosApplicationComposer.class.getName() + " service is registered)";

    private MinosApplicationComposers() {
    }

    /** Résout l'unique racine de composition visible du chargeur de classes de contexte. */
    public static MinosApplicationComposer resolve() {
        return select(ServiceLoader.load(MinosApplicationComposer.class).stream().toList());
    }

    static MinosApplicationComposer select(List<ServiceLoader.Provider<MinosApplicationComposer>> providers) {
        if (providers.isEmpty()) {
            throw new IllegalStateException(MISSING);
        }
        if (providers.size() > 1) {
            String types = providers.stream()
                    .map(provider -> provider.type().getName())
                    .sorted()
                    .collect(Collectors.joining(", "));
            throw new IllegalStateException("MINOS refuses to choose a composition root: " + providers.size()
                    + " " + MinosApplicationComposer.class.getSimpleName() + " services are registered (" + types
                    + "); exactly one minos-bootstrap module must be on the classpath");
        }
        return providers.getFirst().get();
    }
}
