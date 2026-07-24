package com.minos.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MinosApiContractTest {

    private static final List<String> FORBIDDEN_PACKAGES = List.of(
            "com.minos.adapter",
            "com.minos.architecture",
            "com.minos.cli",
            "com.minos.context",
            "com.minos.domain",
            "com.minos.impact",
            "com.minos.query",
            "com.minos.registry",
            "com.minos.store"
    );

    @Test
    void contractVersionIsExplicit() {
        assertEquals("1", MinosApi.CONTRACT_VERSION);
    }

    @Test
    void publicMethodSignaturesDoNotLeakInternalMinosTypes() {
        Arrays.stream(MinosApi.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .forEach(method -> {
                    assertPublicType(method.getGenericReturnType(), method.toGenericString());
                    Arrays.stream(method.getGenericParameterTypes())
                            .forEach(type -> assertPublicType(type, method.toGenericString()));
                    Arrays.stream(method.getGenericExceptionTypes())
                            .forEach(type -> assertPublicType(type, method.toGenericString()));
                });
    }

    @Test
    void dtoRecordComponentsDoNotLeakInternalMinosTypes() {
        Arrays.stream(MinosApi.class.getDeclaredClasses())
                .filter(Class::isRecord)
                .flatMap(type -> Arrays.stream(type.getRecordComponents()))
                .map(RecordComponent::getGenericType)
                .forEach(type -> assertPublicType(type, type.getTypeName()));
    }

    private static void assertPublicType(Type type, String context) {
        String name = type.getTypeName();
        for (String forbiddenPackage : FORBIDDEN_PACKAGES) {
            assertFalse(
                    name.contains(forbiddenPackage),
                    () -> "public API leaks " + forbiddenPackage + " through " + context
            );
        }
    }
}
