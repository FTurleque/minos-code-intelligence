package com.minos.adapter.scip.ingestion;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The descriptor grammar beyond classes and methods: every suffix, the escapes, and every malformed shape. */
class ScipQualifiedNameExtractorGrammarTest {

    private static final String JAVA = "scip-java maven fixture 1.0 ";

    private static Optional<String> java(String descriptors) {
        return ScipQualifiedNameExtractor.extract(JAVA + descriptors, "java");
    }

    // ------------------------------------------------------------------ accepted descriptors

    @Test
    void typeParametersParametersTermsMetaAndMacrosAreNamedByTheirIdentifier() {
        assertEquals("com.minos.Box.T", java("com/minos/Box#[T]").orElseThrow());
        assertEquals("com.minos.Box.item", java("com/minos/Box#(item)").orElseThrow());
        assertEquals("com.minos.Constants.MAX", java("com/minos/Constants#MAX.").orElseThrow());
        assertEquals("com.minos.Thing", java("com/minos/Thing:").orElseThrow());
        assertEquals("com.minos.log", java("com/minos/log!").orElseThrow());
    }

    @Test
    void aTrailingNameWithoutSuffixIsALocalDescriptor() {
        assertEquals("com.minos.Name", java("com/minos/Name").orElseThrow());
    }

    @Test
    void aMethodDisambiguatorMayContainEscapedParenthesesAndBackticks() {
        assertEquals("com.minos.A.run", java("com/minos/A#run(`a)b`).").orElseThrow());
        assertEquals("com.minos.A.run", java("com/minos/A#run(`a``)b`).").orElseThrow());
    }

    @Test
    void identifiersMayUseThePlusMinusDollarAndDigitCharacters() {
        assertEquals("a-b.c+d.$e1", java("a-b/c+d#$e1.").orElseThrow());
    }

    // ------------------------------------------------------------------ language handling

    @Test
    void ecmaScriptModuleDescriptorsAreDroppedForEveryModuleExtension() {
        for (String module : new String[] {"a.ts", "a.tsx", "a.mts", "a.cts", "a.js", "a.jsx", "a.mjs", "a.cjs",
                "a.d.ts", "a.d.mts", "a.d.cts", "A.TS"}) {
            assertEquals("Widget", ScipQualifiedNameExtractor.extract(
                    "scip-typescript npm pkg 1.0.0 src/`" + module + "`/Widget#", "typescript").orElseThrow(), module);
        }
    }

    @Test
    void theTypeScriptSchemeAloneSelectsTheEcmaScriptRulesWhateverTheDeclaredLanguage() {
        String symbol = "scip-typescript npm pkg 1.0.0 src/`a.ts`/Widget#";

        assertEquals("Widget", ScipQualifiedNameExtractor.extract(symbol, null).orElseThrow());
        assertEquals("Widget", ScipQualifiedNameExtractor.extract(symbol, "javascript").orElseThrow());
        assertEquals("Widget", ScipQualifiedNameExtractor.extract(symbol, "JavaScript").orElseThrow());
    }

    @Test
    void otherLanguagesKeepTheModuleLikeDescriptors() {
        assertEquals("src.a.ts.Widget", ScipQualifiedNameExtractor.extract(
                "scip-other generic pkg 1.0.0 src/`a.ts`/Widget#", "kotlin").orElseThrow());
    }

    @Test
    void anEcmaScriptSymbolWithoutAModuleDescriptorKeepsAllItsDescriptors() {
        assertEquals("Foo.bar", ScipQualifiedNameExtractor.extract(
                "scip-typescript npm pkg 1.0.0 Foo#bar().", "typescript").orElseThrow());
    }

    // ------------------------------------------------------------------ refusals

    @Test
    void nullBlankAndLocalSymbolsHaveNoQualifiedName() {
        assertTrue(ScipQualifiedNameExtractor.extract(null, "java").isEmpty());
        assertTrue(ScipQualifiedNameExtractor.extract("   ", "java").isEmpty());
        assertTrue(ScipQualifiedNameExtractor.extract("local 12", "java").isEmpty());
    }

    @Test
    void aSymbolWithoutItsFourHeaderFieldsOrWithoutDescriptorsHasNoQualifiedName() {
        assertTrue(ScipQualifiedNameExtractor.extract("scip-java maven fixture", "java").isEmpty());
        assertTrue(ScipQualifiedNameExtractor.extract("scip-java maven fixture 1.0 ", "java").isEmpty());
    }

    @Test
    void unterminatedOrMismatchedBracketsAreRefused() {
        assertTrue(java("com/minos/Box#[T").isEmpty());
        assertTrue(java("com/minos/Box#[T)").isEmpty());
        assertTrue(java("com/minos/Box#(item").isEmpty());
        assertTrue(java("com/minos/Box#(item]").isEmpty());
        assertTrue(java("com/minos/Box#[]").isEmpty());
    }

    @Test
    void anUnknownSuffixOrAnEmptyNameIsRefused() {
        assertTrue(java("com/minos/Box?").isEmpty());
        assertTrue(java("/x#").isEmpty());
        assertTrue(java("com/``/x#").isEmpty());
    }

    @Test
    void aMethodWithoutItsClosingParenthesisOrItsTermDotIsRefused() {
        assertTrue(java("com/minos/A#run(+1").isEmpty());
        assertTrue(java("com/minos/A#run(+1)").isEmpty());
        assertTrue(java("com/minos/A#run(`+1)").isEmpty());
    }

    @Test
    void anEcmaScriptSymbolMadeOnlyOfAModuleDescriptorHasNoQualifiedName() {
        assertTrue(ScipQualifiedNameExtractor.extract(
                "scip-typescript npm pkg 1.0.0 src/`a.ts`/", "typescript").isEmpty());
    }
}
