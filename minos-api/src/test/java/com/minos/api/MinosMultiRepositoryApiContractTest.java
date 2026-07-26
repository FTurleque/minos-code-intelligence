package com.minos.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MinosMultiRepositoryApiContractTest {

    private static final List<String> FORBIDDEN_PACKAGES = List.of(
            "com.minos.adapter",
            "com.minos.architecture",
            "com.minos.cli",
            "com.minos.context",
            "com.minos.domain",
            "com.minos.git",
            "com.minos.impact",
            "com.minos.mcp",
            "com.minos.query",
            "com.minos.registry",
            "com.minos.store",
            "com.minos.workspace",
            "org.eclipse.jgit"
    );

    @Test
    void multiRepositoryContractVersionIsExplicit() {
        assertEquals("1", MinosMultiRepositoryApi.MULTI_REPOSITORY_CONTRACT_VERSION);
    }

    @Test
    void publicMethodSignaturesDoNotLeakInternalOrJgitTypes() {
        Arrays.stream(MinosMultiRepositoryApi.class.getDeclaredMethods())
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
    void dtoRecordComponentsDoNotLeakInternalOrJgitTypes() {
        Arrays.stream(MinosMultiRepositoryApi.class.getDeclaredClasses())
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
                    () -> "public M12 API leaks " + forbiddenPackage + " through " + context
            );
        }
    }
}
