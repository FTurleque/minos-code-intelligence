package com.minos.adapter.scip;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScipQualifiedNameExtractorTest {

    @Test
    void extractsJavaPackagesTypesMethodsAndParameters() {
        assertEquals("com.minos.fixture.UserService", extract(
                "scip-java maven fixture 1.0 com/minos/fixture/UserService#", "java"));
        assertEquals("com.minos.fixture.UserService.findUser", extract(
                "scip-java maven fixture 1.0 com/minos/fixture/UserService#findUser(+1).", "java"));
        assertEquals("com.minos.fixture.UserService.<init>.repository", extract(
                "scip-java maven fixture 1.0 com/minos/fixture/UserService#`<init>`().(repository)",
                "java"));
    }

    @Test
    void removesTypeScriptModuleDescriptorsAndNormalizesConstructors() {
        String prefix = "scip-typescript npm fixture 1.0.0 src/`user-service.ts`/";

        assertTrue(ScipQualifiedNameExtractor.extract(prefix, "typescript").isEmpty());
        assertEquals("UserService", extract(prefix + "UserService#", "typescript"));
        assertEquals("UserService.findUser", extract(prefix + "UserService#findUser().", "typescript"));
        assertEquals("UserService.constructor.repository", extract(
                prefix + "UserService#`<constructor>`().(repository)", "typescript"));
        assertEquals("getUserName", extract(
                "scip-typescript npm fixture 1.0.0 src/`user-resource.ts`/getUserName().",
                "typescript"));
        assertEquals("Error", extract(
                "scip-typescript npm typescript 5.9.3 lib/`lib.es5.d.ts`/Error#",
                "typescript"));
    }

    @Test
    void honorsEscapedPackageCoordinatesAndDescriptorNames() {
        assertEquals("io strange.User`Service", extract(
                "scip-java maven my  package 1.0 `io strange`/`User``Service`#", "java"));
    }

    @Test
    void rejectsLocalAndMalformedSymbols() {
        assertTrue(ScipQualifiedNameExtractor.extract("local 2", "typescript").isEmpty());
        assertTrue(ScipQualifiedNameExtractor.extract("malformed-global-symbol", "java").isEmpty());
        assertTrue(ScipQualifiedNameExtractor.extract(
                "scip-java maven fixture 1.0 com/minos/`unterminated#", "java").isEmpty());
        assertTrue(ScipQualifiedNameExtractor.extract(
                "scip-java maven fixture 1.0 com/minos/UserService#findUser()", "java").isEmpty());
    }

    private static String extract(String rawSymbol, String language) {
        return ScipQualifiedNameExtractor.extract(rawSymbol, language).orElseThrow();
    }
}
