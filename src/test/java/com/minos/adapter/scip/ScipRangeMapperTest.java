package com.minos.adapter.scip;

import com.minos.domain.PositionEncoding;
import org.junit.jupiter.api.Test;
import org.scip_code.scip.MultiLineRange;
import org.scip_code.scip.Occurrence;
import org.scip_code.scip.SingleLineRange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScipRangeMapperTest {

    private final ScipRangeMapper mapper = new ScipRangeMapper();

    @Test
    void mapsTypedSingleLineRangeAndKeepsPositionEncoding() {
        Occurrence occurrence = Occurrence.newBuilder()
                .setSingleLineRange(SingleLineRange.newBuilder()
                        .setLine(4)
                        .setStartCharacter(3)
                        .setEndCharacter(11))
                .build();

        var location = mapper.map(
                "file-1",
                occurrence,
                org.scip_code.scip.PositionEncoding.UTF16CodeUnitOffsetFromLineStart
        ).orElseThrow();

        assertEquals(5, location.startLine());
        assertEquals(3, location.startColumn());
        assertEquals(5, location.endLine());
        assertEquals(11, location.endColumn());
        assertEquals(PositionEncoding.UTF16_CODE_UNITS, location.positionEncoding());
    }

    @Test
    void mapsTypedMultiLineRange() {
        Occurrence occurrence = Occurrence.newBuilder()
                .setMultiLineRange(MultiLineRange.newBuilder()
                        .setStartLine(2)
                        .setStartCharacter(1)
                        .setEndLine(5)
                        .setEndCharacter(7))
                .build();

        var location = mapper.map(
                "file-2",
                occurrence,
                org.scip_code.scip.PositionEncoding.UTF8CodeUnitOffsetFromLineStart
        ).orElseThrow();

        assertEquals(3, location.startLine());
        assertEquals(1, location.startColumn());
        assertEquals(6, location.endLine());
        assertEquals(7, location.endColumn());
        assertEquals(PositionEncoding.UTF8_CODE_UNITS, location.positionEncoding());
    }

    @Test
    @SuppressWarnings("deprecation")
    void fallsBackToDeprecatedPackedRange() {
        Occurrence occurrence = Occurrence.newBuilder()
                .addRange(8)
                .addRange(2)
                .addRange(14)
                .build();

        var location = mapper.map(
                "file-3",
                occurrence,
                org.scip_code.scip.PositionEncoding.UnspecifiedPositionEncoding
        ).orElseThrow();

        assertEquals(9, location.startLine());
        assertEquals(2, location.startColumn());
        assertEquals(9, location.endLine());
        assertEquals(14, location.endColumn());
        assertEquals(PositionEncoding.UNKNOWN, location.positionEncoding());
    }

    @Test
    void returnsEmptyWhenNoRangeIsAvailable() {
        var location = mapper.map(
                "file-4",
                Occurrence.getDefaultInstance(),
                org.scip_code.scip.PositionEncoding.UTF32CodeUnitOffsetFromLineStart
        );

        assertTrue(location.isEmpty());
    }
}
