package com.minos.adapter.scip;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScipDescriptorNameExtractorTest {

    @Test
    void extractsOnlyTheLastGlobalDescriptor() {
        assertEquals("UserService", ScipDescriptorNameExtractor.extract(
                "scip-typescript npm fixture 1.0.0 src/`user-service.ts`/UserService#"
        ).orElseThrow());
        assertEquals("findUser", ScipDescriptorNameExtractor.extract(
                "scip-typescript npm fixture 1.0.0 src/`user-service.ts`/UserService#findUser()."
        ).orElseThrow());
        assertEquals("repository", ScipDescriptorNameExtractor.extract(
                "scip-typescript npm fixture 1.0.0 src/`user-service.ts`/UserService#`<constructor>`().(repository)"
        ).orElseThrow());
        assertEquals("<constructor>", ScipDescriptorNameExtractor.extract(
                "scip-typescript npm fixture 1.0.0 src/`user-service.ts`/UserService#`<constructor>`()."
        ).orElseThrow());
    }

    @Test
    void doesNotInventNamesForLocalOrModuleSymbols() {
        assertTrue(ScipDescriptorNameExtractor.extract("local 2").isEmpty());
        assertTrue(ScipDescriptorNameExtractor.extract("malformed-global-symbol").isEmpty());
        assertTrue(ScipDescriptorNameExtractor.extract(
                "scip-typescript npm fixture 1.0.0 src/`user-service.ts`/"
        ).isEmpty());
    }
}
