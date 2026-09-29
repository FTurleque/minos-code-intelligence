package com.minos.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A3 / ADR 0044 — découverte du point d'entrée {@code minos mcp} : échec explicite sans fournisseur,
 * refus avec plusieurs, recherche dans le seul chargeur de classes de MINOS. minos-cli n'a pas minos-app
 * sur son classpath de test : la découverte réelle y voit zéro fournisseur.
 */
class McpLaunchRoutesTest {

    @Test
    void resolutionFailsExplicitlyWhenNoEntryPointIsRegistered(@TempDir Path temp) {
        IllegalStateException failure = assertThrows(IllegalStateException.class, McpLaunchRoutes::resolve);

        assertEquals("MINOS MCP entry point is missing: the minos-app module must be on the classpath"
                + " (no com.minos.cli.McpLaunchRoute service is registered)", failure.getMessage());
        assertFalse(failure.getMessage().contains(temp.toString()));
    }

    @Test
    void resolutionRefusesToChooseBetweenSeveralEntryPoints(@TempDir Path temp) throws Exception {
        try (URLClassLoader loader = loaderRegistering(temp, SecondRoute.class, FirstRoute.class)) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> McpLaunchRoutes.resolve(loader));

            assertEquals("MINOS refuses to choose an MCP entry point: 2 McpLaunchRoute services are registered ("
                    + FirstRoute.class.getName() + ", " + SecondRoute.class.getName()
                    + "); exactly one minos-app module must be on the classpath", failure.getMessage());
            assertFalse(failure.getMessage().contains(temp.toString()));
        }
    }

    @Test
    void theSingleRegisteredEntryPointIsReturned(@TempDir Path temp) throws Exception {
        try (URLClassLoader loader = loaderRegistering(temp, FirstRoute.class)) {
            assertInstanceOf(FirstRoute.class, McpLaunchRoutes.resolve(loader));
        }
    }

    @Test
    void theThreadContextClassLoaderIsNeverConsulted(@TempDir Path temp) throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = loaderRegistering(temp, FirstRoute.class)) {
            Thread.currentThread().setContextClassLoader(loader);
            assertEquals(McpLaunchRoutes.MISSING,
                    assertThrows(IllegalStateException.class, McpLaunchRoutes::resolve).getMessage());
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static URLClassLoader loaderRegistering(Path temp, Class<?>... providers) throws Exception {
        Path services = Files.createDirectories(temp.resolve("classes").resolve("META-INF").resolve("services"));
        StringBuilder lines = new StringBuilder();
        for (Class<?> provider : providers) {
            lines.append(provider.getName()).append('\n');
        }
        Files.writeString(services.resolve(McpLaunchRoute.class.getName()), lines, StandardCharsets.UTF_8);
        return new URLClassLoader(new URL[]{temp.resolve("classes").toUri().toURL()},
                McpLaunchRoutesTest.class.getClassLoader());
    }

    public static final class FirstRoute implements McpLaunchRoute {
        @Override
        public int run(Path home) {
            return 0;
        }
    }

    public static final class SecondRoute implements McpLaunchRoute {
        @Override
        public int run(Path home) {
            return 0;
        }
    }
}
