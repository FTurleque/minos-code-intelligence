package com.minos.architecture;

import com.minos.application.resolution.ProjectResolver;
import com.minos.discovery.ProjectDiscoveryService;
import com.minos.registry.ProjectRegistry;
import com.minos.store.CodeKnowledgeSnapshotStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A4 (ADR 0045) : un seul constructeur, privé, et un seul point d'entrée nommé,
 * {@code defaults(ProjectRegistry, CodeKnowledgeSnapshotStore, ProjectDiscoveryService)}.
 * Échoue si un constructeur télescopique réapparaît.
 */
class LocalProjectArchitectureQuerySurfaceTest {

    @Test
    void declaresExactlyOnePrivateConstructor() {
        Constructor<?>[] constructors = LocalProjectArchitectureQuery.class.getDeclaredConstructors();

        assertEquals(1, constructors.length, () -> "declared constructors: " + Arrays.toString(constructors));
        assertTrue(Modifier.isPrivate(constructors[0].getModifiers()), constructors[0]::toString);
        assertEquals(List.of(ProjectResolver.class, CodeKnowledgeSnapshotStore.class, ProjectDiscoveryService.class),
                List.of(constructors[0].getParameterTypes()));
    }

    @Test
    void exposesDefaultsAsTheOnlyNamedEntryPoint() {
        List<Method> factories = Arrays.stream(LocalProjectArchitectureQuery.class.getDeclaredMethods())
                .filter(method -> Modifier.isStatic(method.getModifiers()))
                .filter(method -> LocalProjectArchitectureQuery.class.isAssignableFrom(method.getReturnType()))
                .toList();

        assertEquals(1, factories.size(), () -> "static factories: " + factories);
        Method defaults = factories.getFirst();
        assertEquals("defaults", defaults.getName());
        assertTrue(Modifier.isPublic(defaults.getModifiers()), defaults::toString);
        assertEquals(List.of(ProjectRegistry.class, CodeKnowledgeSnapshotStore.class, ProjectDiscoveryService.class),
                List.of(defaults.getParameterTypes()));
    }

    @Test
    void defaultsBuildsAQueryAndRejectsMissingCollaboratorsByName() throws Exception {
        Method defaults = LocalProjectArchitectureQuery.class.getMethod("defaults",
                ProjectRegistry.class, CodeKnowledgeSnapshotStore.class, ProjectDiscoveryService.class);
        ProjectRegistry registry = unused(ProjectRegistry.class);
        CodeKnowledgeSnapshotStore snapshots = unused(CodeKnowledgeSnapshotStore.class);
        ProjectDiscoveryService discovery = new ProjectDiscoveryService();

        assertInstanceOf(LocalProjectArchitectureQuery.class, defaults.invoke(null, registry, snapshots, discovery));
        assertEquals("registry", nullPointerMessage(defaults, null, snapshots, discovery));
        assertEquals("snapshotStore", nullPointerMessage(defaults, registry, null, discovery));
        assertEquals("discoveryService", nullPointerMessage(defaults, registry, snapshots, null));
    }

    private static String nullPointerMessage(Method defaults, Object... arguments) {
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> defaults.invoke(null, arguments));
        NullPointerException cause = assertInstanceOf(NullPointerException.class, failure.getCause());
        assertNotNull(cause.getMessage());
        return cause.getMessage();
    }

    private static <T> T unused(Class<T> contract) {
        return contract.cast(Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract},
                (proxy, method, arguments) -> {
                    throw new AssertionError("construction must not call " + contract.getSimpleName() + "." + method.getName());
                }));
    }
}
