package io.github.fturleque.minos.adapter.scip;

import io.github.fturleque.minos.domain.PositionEncoding;
import io.github.fturleque.minos.domain.SymbolLocation;
import org.scip_code.scip.Occurrence;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Convertit les ranges SCIP vers le modèle d'emplacement MINOS.
 */
final class ScipRangeMapper {

    Optional<SymbolLocation> map(
            String fileId,
            Occurrence occurrence,
            org.scip_code.scip.PositionEncoding scipEncoding) {
        Objects.requireNonNull(fileId, "fileId");
        Objects.requireNonNull(occurrence, "occurrence");
        Objects.requireNonNull(scipEncoding, "scipEncoding");

        PositionEncoding encoding = mapEncoding(scipEncoding);

        if (occurrence.hasSingleLineRange()) {
            var range = occurrence.getSingleLineRange();
            return Optional.of(new SymbolLocation(
                    fileId,
                    range.getLine() + 1,
                    range.getStartCharacter(),
                    range.getLine() + 1,
                    range.getEndCharacter(),
                    encoding
            ));
        }

        if (occurrence.hasMultiLineRange()) {
            var range = occurrence.getMultiLineRange();
            return Optional.of(new SymbolLocation(
                    fileId,
                    range.getStartLine() + 1,
                    range.getStartCharacter(),
                    range.getEndLine() + 1,
                    range.getEndCharacter(),
                    encoding
            ));
        }

        return mapDeprecatedRange(fileId, occurrence, encoding);
    }

    @SuppressWarnings("deprecation")
    private Optional<SymbolLocation> mapDeprecatedRange(
            String fileId,
            Occurrence occurrence,
            PositionEncoding encoding) {
        List<Integer> range = occurrence.getRangeList();

        if (range.size() == 3) {
            int line = range.get(0) + 1;
            return Optional.of(new SymbolLocation(
                    fileId,
                    line,
                    range.get(1),
                    line,
                    range.get(2),
                    encoding
            ));
        }

        if (range.size() == 4) {
            return Optional.of(new SymbolLocation(
                    fileId,
                    range.get(0) + 1,
                    range.get(1),
                    range.get(2) + 1,
                    range.get(3),
                    encoding
            ));
        }

        return Optional.empty();
    }

    private PositionEncoding mapEncoding(org.scip_code.scip.PositionEncoding encoding) {
        return switch (encoding) {
            case UTF8CodeUnitOffsetFromLineStart -> PositionEncoding.UTF8_CODE_UNITS;
            case UTF16CodeUnitOffsetFromLineStart -> PositionEncoding.UTF16_CODE_UNITS;
            case UTF32CodeUnitOffsetFromLineStart -> PositionEncoding.UTF32_CODE_UNITS;
            case UnspecifiedPositionEncoding, UNRECOGNIZED -> PositionEncoding.UNKNOWN;
        };
    }
}
