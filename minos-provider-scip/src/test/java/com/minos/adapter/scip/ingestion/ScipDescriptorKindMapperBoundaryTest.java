package com.minos.adapter.scip.ingestion;

import com.minos.domain.SymbolKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A SCIP descriptor only encodes a few kinds. The mapper infers those and nothing else: anything it cannot read
 * with certainty stays {@code OTHER} instead of being promoted to a plausible kind.
 */
class ScipDescriptorKindMapperBoundaryTest {

    private final ScipDescriptorKindMapper mapper = new ScipDescriptorKindMapper();

    @Test
    void anAbsentBlankOrLocalSymbolHasNoInferredKind() {
        assertEquals(SymbolKind.OTHER, mapper.map(null));
        assertEquals(SymbolKind.OTHER, mapper.map("  "));
        assertEquals(SymbolKind.OTHER, mapper.map("local 12"));
    }

    @Test
    void aTypeDescriptorIsAType() {
        assertEquals(SymbolKind.TYPE, mapper.map("scip-java maven . . example/Greeting#"));
    }

    @Test
    void aTermOrParameterDescriptorHasNoInferredKind() {
        assertEquals(SymbolKind.OTHER, mapper.map("scip-java maven . . example/Greeting#name."));
        assertEquals(SymbolKind.OTHER, mapper.map("scip-java maven . . example/Greeting#greet().(name)"));
        assertEquals(SymbolKind.OTHER, mapper.map("scip-java maven . . example/"));
    }

    @Test
    void aMethodDescriptorIsAMethodInsideATypeAndAFunctionInsideANamespace() {
        assertEquals(SymbolKind.METHOD, mapper.map("scip-java maven . . example/Greeting#greet()."));
        assertEquals(SymbolKind.FUNCTION, mapper.map("scip-go gomod . . example/greet()."));
    }

    @Test
    void aMethodDescriptorUnderATermOrWithoutAnyParentHasNoInferredKind() {
        assertEquals(SymbolKind.OTHER, mapper.map("pkg/Holder.greet()."),
                "the parent is a term, which does not say whether this is a method or a function");
        assertEquals(SymbolKind.OTHER, mapper.map("greet()."), "no parent descriptor at all");
    }

    @Test
    void aSeparatorInsideAnEscapedNameIsNotAParentSeparator() {
        assertEquals(SymbolKind.METHOD, mapper.map("pkg/Holder#`a/b.c`()."),
                "the slash and the dot belong to the escaped method name; the parent is the type");
        assertEquals(SymbolKind.FUNCTION, mapper.map("pkg/`name``with``ticks`()."),
                "a doubled backtick is an escaped backtick, not the end of the name");
    }
}
