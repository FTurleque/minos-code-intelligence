package com.minos.adapter.scip;

import org.junit.jupiter.api.Test;
import org.scip_code.scip.Document;
import org.scip_code.scip.Index;
import org.scip_code.scip.MultiLineRange;
import org.scip_code.scip.Occurrence;
import org.scip_code.scip.SingleLineRange;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScipLegacyRangeCompatibilityExperimentTest {

    @Test
    @SuppressWarnings("deprecation")
    void copiesTypedRangesIntoLegacyFieldsWithoutChangingOtherOccurrenceFacts() {
        Occurrence singleLine = Occurrence.newBuilder()
                .setSingleLineRange(SingleLineRange.newBuilder()
                        .setLine(3)
                        .setStartCharacter(4)
                        .setEndCharacter(9))
                .setSingleLineEnclosingRange(SingleLineRange.newBuilder()
                        .setLine(3)
                        .setStartCharacter(0)
                        .setEndCharacter(15))
                .setSymbol("single")
                .setSymbolRoles(1)
                .build();
        Occurrence multiLine = Occurrence.newBuilder()
                .setMultiLineRange(MultiLineRange.newBuilder()
                        .setStartLine(6)
                        .setStartCharacter(2)
                        .setEndLine(8)
                        .setEndCharacter(5))
                .setMultiLineEnclosingRange(MultiLineRange.newBuilder()
                        .setStartLine(5)
                        .setStartCharacter(0)
                        .setEndLine(9)
                        .setEndCharacter(1))
                .setSymbol("multi")
                .build();
        Occurrence legacy = Occurrence.newBuilder()
                .addAllRange(List.of(10, 1, 4))
                .setSymbol("legacy")
                .build();

        Index source = Index.newBuilder()
                .addDocuments(Document.newBuilder()
                        .setRelativePath("Example.java")
                        .addOccurrences(singleLine)
                        .addOccurrences(multiLine)
                        .addOccurrences(legacy))
                .build();

        var result = ScipLegacyRangeCompatibilityExperiment.convert(source);
        List<Occurrence> converted = result.index().getDocuments(0).getOccurrencesList();

        assertEquals(List.of(3, 4, 9), converted.get(0).getRangeList());
        assertEquals(List.of(3, 0, 15), converted.get(0).getEnclosingRangeList());
        assertEquals(Occurrence.TypedRangeCase.TYPEDRANGE_NOT_SET,
                converted.get(0).getTypedRangeCase());
        assertEquals(Occurrence.TypedEnclosingRangeCase.TYPEDENCLOSINGRANGE_NOT_SET,
                converted.get(0).getTypedEnclosingRangeCase());
        assertEquals("single", converted.get(0).getSymbol());
        assertEquals(1, converted.get(0).getSymbolRoles());

        assertEquals(List.of(6, 2, 8, 5), converted.get(1).getRangeList());
        assertEquals(List.of(5, 0, 9, 1), converted.get(1).getEnclosingRangeList());
        assertEquals(List.of(10, 1, 4), converted.get(2).getRangeList());
        assertEquals(3, result.occurrences());
        assertEquals(2, result.typedRangesConverted());
        assertEquals(2, result.typedEnclosingRangesConverted());
        assertEquals(1, result.legacyRangesRetained());
        assertEquals(0, result.missingRanges());
    }
}
