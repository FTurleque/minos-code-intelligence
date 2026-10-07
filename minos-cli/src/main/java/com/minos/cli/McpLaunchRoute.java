package com.minos.cli;

import java.nio.file.Path;

/**
 * Point d'entrée de {@code minos mcp}, fourni par l'assemblage final ({@code minos-app}) et découvert par
 * {@code ServiceLoader} (ADR 0044).
 *
 * <p>{@link MinosLauncher} vit dans {@code minos-cli}, qui ne peut pas dépendre de {@code minos-mcp} ; la route
 * MCP (serveur natif ou transport Docker) est donc fournie par {@code minos-app} via
 * {@code META-INF/services/com.minos.cli.McpLaunchRoute}. Le lanceur l'appelle avant toute ouverture de
 * {@code MinosApplication}, avec le home MINOS résolu mais non ouvert.</p>
 */
public interface McpLaunchRoute {

    /**
     * Exécute {@code minos mcp} pour ce home et rend le code de sortie du processus.
     *
     * @param home home MINOS résolu, pas encore ouvert
     * @return code de sortie
     * @throws Exception toute erreur, rapportée par le lanceur sans chemin
     */
    int run(Path home) throws Exception;
}
