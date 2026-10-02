package com.minos.cli;

import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

/**
 * Découverte du point d'entrée {@code minos mcp} (ADR 0044), déterministe et en échec rapide, sur le modèle
 * de la découverte de la racine de composition (ADR 0042).
 *
 * <p>Exactement un {@link McpLaunchRoute} doit être enregistré ({@code META-INF/services}, module
 * {@code minos-app}). Aucun : échec explicite qui nomme le module manquant. Plusieurs : refus, jamais un
 * choix arbitraire. Les messages ne citent que des noms de classes, jamais de chemin ; le lanceur les
 * rapporte par {@code PublicErrorMessages}. La recherche se fait dans le seul chargeur de classes de MINOS
 * (celui de {@link McpLaunchRoute}), jamais dans le chargeur de contexte du thread.</p>
 */
final class McpLaunchRoutes {

    static final String MISSING = "MINOS MCP entry point is missing: the minos-app module must be on the"
            + " classpath (no " + McpLaunchRoute.class.getName() + " service is registered)";

    private McpLaunchRoutes() {
    }

    /** Résout l'unique point d'entrée MCP visible du chargeur de classes de MINOS. */
    static McpLaunchRoute resolve() {
        return resolve(McpLaunchRoute.class.getClassLoader());
    }

    static McpLaunchRoute resolve(ClassLoader loader) {
        return select(ServiceLoader.load(McpLaunchRoute.class, loader).stream().toList());
    }

    private static McpLaunchRoute select(List<ServiceLoader.Provider<McpLaunchRoute>> providers) {
        if (providers.isEmpty()) {
            throw new IllegalStateException(MISSING);
        }
        if (providers.size() > 1) {
            String types = providers.stream()
                    .map(provider -> provider.type().getName())
                    .sorted()
                    .collect(Collectors.joining(", "));
            throw new IllegalStateException("MINOS refuses to choose an MCP entry point: " + providers.size()
                    + " " + McpLaunchRoute.class.getSimpleName() + " services are registered (" + types
                    + "); exactly one minos-app module must be on the classpath");
        }
        return providers.getFirst().get();
    }
}
