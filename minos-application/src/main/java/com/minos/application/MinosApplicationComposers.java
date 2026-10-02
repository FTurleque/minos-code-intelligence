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
 *
 * <p>V48 — la recherche se fait dans le seul chargeur de classes de MINOS (celui de
 * {@link MinosApplicationComposer}), jamais dans le chargeur de contexte du thread. Avant A2, l'ouverture
 * locale ne dépendait d'aucun {@code ServiceLoader} : un hôte embarqué qui positionne un autre chargeur
 * de contexte ne doit ni masquer la racine de composition livrée avec MINOS, ni en injecter une autre.
 * C'est l'option la plus simple ; consulter aussi le chargeur de contexte ajouterait une seconde source,
 * une déduplication, et rouvrirait l'injection par l'hôte.</p>
 */
public final class MinosApplicationComposers {

    static final String MISSING = "MINOS composition root is missing: the minos-bootstrap module must be on the"
            + " classpath (no " + MinosApplicationComposer.class.getName() + " service is registered)";

    private MinosApplicationComposers() {
    }

    /** Résout l'unique racine de composition visible du chargeur de classes de MINOS. */
    public static MinosApplicationComposer resolve() {
        return resolve(MinosApplicationComposer.class.getClassLoader());
    }

    static MinosApplicationComposer resolve(ClassLoader loader) {
        return select(ServiceLoader.load(MinosApplicationComposer.class, loader).stream().toList());
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
