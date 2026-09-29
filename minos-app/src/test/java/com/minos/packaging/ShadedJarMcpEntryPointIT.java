package com.minos.packaging;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A3 / ADR 0044 — contrat de packaging du point d'entrée {@code minos mcp} : le JAR distribué (ombré) déclare
 * exactement un {@code com.minos.cli.McpLaunchRoute} (fichier fusionné par le {@code ServicesResourceTransformer}),
 * contient sa classe, et la découverte réelle du lanceur, faite dans le chargeur de MINOS, le trouve.
 */
class ShadedJarMcpEntryPointIT {

    private static final String ROUTE_SERVICE = "META-INF/services/com.minos.cli.McpLaunchRoute";
    private static final String PROVIDER = "com.minos.app.McpLaunchRouteProvider";

    @Test
    void shadedJarDeclaresExactlyOneMcpEntryPoint() throws IOException {
        try (JarFile jar = new JarFile(shadedJar().toFile())) {
            assertEquals(List.of(PROVIDER), serviceLines(jar, ROUTE_SERVICE));
            assertNotNull(jar.getJarEntry("com/minos/app/McpLaunchRouteProvider.class"));
            assertNotNull(jar.getJarEntry("com/minos/cli/McpLaunchRoute.class"));
            assertNotNull(jar.getJarEntry("com/minos/cli/MinosLauncher.class"));
        }
    }

    @Test
    void realDiscoveryInsideTheShadedJarFindsTheSingleMcpEntryPoint() throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{shadedJar().toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Thread.currentThread().setContextClassLoader(ClassLoader.getPlatformClassLoader());
            Class<?> routes = Class.forName("com.minos.cli.McpLaunchRoutes", true, loader);
            Method resolve = routes.getDeclaredMethod("resolve");
            resolve.setAccessible(true);
            Object route = resolve.invoke(null);
            assertEquals(PROVIDER, route.getClass().getName());
            assertEquals(loader, route.getClass().getClassLoader());
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static Path shadedJar() {
        Path shadedJar = Path.of(System.getProperty("minos.shaded.jar")).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(shadedJar), () -> "missing shaded JAR: " + shadedJar);
        return shadedJar;
    }

    private static List<String> serviceLines(JarFile jar, String name) throws IOException {
        JarEntry entry = jar.getJarEntry(name);
        assertNotNull(entry, () -> "missing service declaration " + name);
        try (InputStream input = jar.getInputStream(entry)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        }
    }
}
