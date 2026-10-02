package com.minos.bootstrap;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A1-3 / A2 — aucun chemin de production ne contourne le refus anticipé de {@code remote index} : le
 * JAR de production n'offre aucun point public pour injecter une sélection de sandbox ou une fabrique
 * de workers dans le moteur d'indexation distante. Seule la composition (production(home), package
 * de la racine de composition) le construit ; les tests passent par les sources de test.
 */
class LocalRemoteIndexingRuntimeInjectionTest {

    @Test
    void theProductionJarExposesNoPublicInjectionPoint() {
        List<String> publicConstructors = Arrays.stream(LocalRemoteIndexingRuntime.class.getConstructors())
                .map(Object::toString)
                .toList();
        List<String> publicSelectionSeams = Arrays.stream(LocalRemoteIndexingRuntime.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Arrays.stream(method.getParameterTypes()).anyMatch(Supplier.class::isAssignableFrom))
                .map(Object::toString)
                .toList();

        assertEquals(List.of(), publicConstructors, "no public constructor may accept a sandbox selection");
        assertEquals(List.of(), publicSelectionSeams, "no public method may accept a sandbox selection");
    }
}
