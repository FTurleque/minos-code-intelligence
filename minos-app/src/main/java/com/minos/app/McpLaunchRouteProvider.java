package com.minos.app;

import com.minos.cli.McpLaunchRoute;

import java.nio.file.Path;

/**
 * Fournit à {@code com.minos.cli.MinosLauncher} la route {@code minos mcp} de l'assemblage final : le
 * {@link McpBackendRouter} (serveur MCP natif ou transport Docker), enregistré par
 * {@code META-INF/services/com.minos.cli.McpLaunchRoute} (ADR 0044).
 */
public final class McpLaunchRouteProvider implements McpLaunchRoute {

    @Override
    public int run(Path home) throws Exception {
        return new McpBackendRouter().run(home);
    }
}
