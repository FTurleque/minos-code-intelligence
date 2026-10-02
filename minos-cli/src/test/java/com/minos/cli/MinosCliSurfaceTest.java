package com.minos.cli;

import com.minos.application.ProjectSymbolQuery;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A4 (ADR 0045) : {@link MinosCli} n'a qu'un constructeur, privé, qui reçoit son {@code Builder} ;
 * le seul point d'entrée est {@code MinosCli.builder(ProjectSymbolQuery)}. Les mutateurs publics du
 * {@code Builder} sont exactement ceux qu'ouvraient les anciens constructeurs publics ; les autres
 * restent package-private. Échoue si un constructeur télescopique réapparaît ou si une visibilité change.
 */
class MinosCliSurfaceTest {

    private static final String BUILDER = "com.minos.cli.MinosCli$Builder";

    @Test
    void declaresExactlyOnePrivateConstructorTakingTheBuilder() throws Exception {
        Constructor<?>[] constructors = MinosCli.class.getDeclaredConstructors();

        assertEquals(1, constructors.length, () -> "declared constructors: " + Arrays.toString(constructors));
        assertTrue(Modifier.isPrivate(constructors[0].getModifiers()), constructors[0]::toString);
        assertEquals(List.of(Class.forName(BUILDER)), List.of(constructors[0].getParameterTypes()));
    }

    @Test
    void exposesBuilderAsTheOnlyNamedEntryPoint() throws Exception {
        Class<?> builder = Class.forName(BUILDER);
        List<Method> entryPoints = Arrays.stream(MinosCli.class.getDeclaredMethods())
                .filter(method -> Modifier.isStatic(method.getModifiers()))
                .filter(method -> method.getReturnType() == builder || method.getReturnType() == MinosCli.class)
                .toList();

        assertEquals(1, entryPoints.size(), () -> "static entry points: " + entryPoints);
        Method entryPoint = entryPoints.getFirst();
        assertEquals("builder", entryPoint.getName());
        assertTrue(Modifier.isPublic(entryPoint.getModifiers()), entryPoint::toString);
        assertEquals(List.of(ProjectSymbolQuery.class), List.of(entryPoint.getParameterTypes()));
        assertTrue(Modifier.isPublic(builder.getModifiers()) && Modifier.isStatic(builder.getModifiers())
                && Modifier.isFinal(builder.getModifiers()), builder::toString);
    }

    @Test
    void builderKeepsTheVisibilityOfTheFormerConstructors() throws Exception {
        Class<?> builder = Class.forName(BUILDER);
        Map<String, String> visibility = new TreeMap<>();
        for (Method method : builder.getDeclaredMethods()) {
            if (method.isSynthetic() || Modifier.isPrivate(method.getModifiers())) continue;
            visibility.put(method.getName(), Modifier.isPublic(method.getModifiers()) ? "public" : "package");
        }
        Map<String, String> expected = new TreeMap<>(Map.ofEntries(
                Map.entry("projectOperations", "public"),
                Map.entry("architectureQuery", "public"),
                Map.entry("impactQuery", "public"),
                Map.entry("build", "public"),
                Map.entry("nexusExportCommand", "package"),
                Map.entry("autonomousOperations", "package"),
                Map.entry("home", "package"),
                Map.entry("providerPlatformService", "package"),
                Map.entry("providerPlatformServiceSupplier", "package"),
                Map.entry("gitIntelligence", "package"),
                Map.entry("remoteIndexOperations", "package"),
                Map.entry("runtimeIntelligenceService", "package"),
                Map.entry("runtimeIntelligenceServiceSupplier", "package"),
                Map.entry("hostedControlPlaneService", "package"),
                Map.entry("hostedControlPlaneServiceSupplier", "package"),
                Map.entry("resumeStatus", "package")));

        assertEquals(expected, visibility);
        assertEquals(0, builder.getConstructors().length, "the Builder is only reachable through MinosCli.builder");
    }
}
