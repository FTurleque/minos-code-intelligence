package com.minos.adapter.scip;

import com.minos.domain.SymbolKind;
import org.junit.jupiter.api.Test;
import org.scip_code.scip.SymbolInformation;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScipSymbolKindMapperTest {

    private final ScipSymbolKindMapper mapper = new ScipSymbolKindMapper();

    @Test
    void mapsCommonObjectOrientedKinds() {
        assertEquals(SymbolKind.CLASS, mapper.map(SymbolInformation.Kind.Class));
        assertEquals(SymbolKind.INTERFACE, mapper.map(SymbolInformation.Kind.Interface));
        assertEquals(SymbolKind.STRUCT, mapper.map(SymbolInformation.Kind.Struct));
        assertEquals(SymbolKind.ENUM, mapper.map(SymbolInformation.Kind.Enum));
        assertEquals(SymbolKind.TRAIT, mapper.map(SymbolInformation.Kind.Trait));
    }

    @Test
    void collapsesSpecializedMethodAndPropertyKindsWithoutInventingPrecision() {
        assertEquals(SymbolKind.METHOD, mapper.map(SymbolInformation.Kind.AbstractMethod));
        assertEquals(SymbolKind.METHOD, mapper.map(SymbolInformation.Kind.StaticMethod));
        assertEquals(SymbolKind.PROPERTY, mapper.map(SymbolInformation.Kind.Getter));
        assertEquals(SymbolKind.PROPERTY, mapper.map(SymbolInformation.Kind.StaticProperty));
    }

    @Test
    void mapsUnsupportedFineGrainedKindToOther() {
        assertEquals(SymbolKind.OTHER, mapper.map(SymbolInformation.Kind.Parameter));
        assertEquals(SymbolKind.OTHER, mapper.map(SymbolInformation.Kind.UnspecifiedKind));
        assertEquals(SymbolKind.OTHER, mapper.map(null));
    }

    @Test
    void infersOnlyKindsEncodedByDescriptorsWhenProviderKindIsUnspecified() {
        String prefix = "scip-typescript npm fixture 1.0.0 src/`greeting.ts`/";

        assertEquals(SymbolKind.TYPE, mapper.map(
                SymbolInformation.Kind.UnspecifiedKind, prefix + "GreetingPort#"));
        assertEquals(SymbolKind.METHOD, mapper.map(
                SymbolInformation.Kind.UnspecifiedKind, prefix + "GreetingPort#greet()."));
        assertEquals(SymbolKind.CONSTRUCTOR, mapper.map(
                SymbolInformation.Kind.UnspecifiedKind,
                prefix + "GreetingService#`<constructor>`()."));
        assertEquals(SymbolKind.FUNCTION, mapper.map(
                SymbolInformation.Kind.UnspecifiedKind, prefix + "greet()."));
        assertEquals(SymbolKind.OTHER, mapper.map(
                SymbolInformation.Kind.UnspecifiedKind, prefix + "greeting."));
    }

    @Test
    void explicitProviderKindTakesPrecedenceOverDescriptorFallback() {
        String methodDescriptor =
                "scip-typescript npm fixture 1.0.0 src/`greeting.ts`/GreetingPort#greet().";

        assertEquals(SymbolKind.OTHER, mapper.map(
                SymbolInformation.Kind.Parameter, methodDescriptor));
        assertEquals(SymbolKind.INTERFACE, mapper.map(
                SymbolInformation.Kind.Interface, methodDescriptor));
    }
}
