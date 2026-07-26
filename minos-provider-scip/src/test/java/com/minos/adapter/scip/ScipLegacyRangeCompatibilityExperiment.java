package com.minos.adapter.scip;

import org.scip_code.scip.Document;
import org.scip_code.scip.Index;
import org.scip_code.scip.Occurrence;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Export expérimental d'une copie SCIP utilisant les anciens tableaux de
 * positions attendus par Glean 0.2.0.1.
 *
 * <p>Cette classe reste dans les sources de test : elle mesure un coût de
 * compatibilité fournisseur pendant M0 et ne devient pas un contrat MINOS.</p>
 */
public final class ScipLegacyRangeCompatibilityExperiment {

    private ScipLegacyRangeCompatibilityExperiment() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: ScipLegacyRangeCompatibilityExperiment <input.scip> <output.scip>"
            );
        }

        Path input = Path.of(arguments[0]).toAbsolutePath().normalize();
        Path output = Path.of(arguments[1]).toAbsolutePath().normalize();
        Index index = new ScipIndexReader().read(input);
        ConversionResult result = convert(index);
        writeTransactionally(result.index(), output);

        metric("documents", result.index().getDocumentsCount());
        metric("occurrences", result.occurrences());
        metric("typedRangesConverted", result.typedRangesConverted());
        metric("typedEnclosingRangesConverted", result.typedEnclosingRangesConverted());
        metric("legacyRangesRetained", result.legacyRangesRetained());
        metric("missingRanges", result.missingRanges());
        metric("outputBytes", Files.size(output));
    }

    @SuppressWarnings("deprecation")
    static ConversionResult convert(Index index) {
        Index.Builder convertedIndex = index.toBuilder().clearDocuments();
        int occurrences = 0;
        int typedRangesConverted = 0;
        int typedEnclosingRangesConverted = 0;
        int legacyRangesRetained = 0;
        int missingRanges = 0;

        for (Document document : index.getDocumentsList()) {
            Document.Builder convertedDocument = document.toBuilder().clearOccurrences();
            for (Occurrence occurrence : document.getOccurrencesList()) {
                occurrences++;
                Occurrence.Builder converted = occurrence.toBuilder();

                List<Integer> legacyRange = typedRange(occurrence);
                if (!legacyRange.isEmpty()) {
                    converted.clearRange().addAllRange(legacyRange).clearTypedRange();
                    typedRangesConverted++;
                } else if (occurrence.getRangeCount() > 0) {
                    legacyRangesRetained++;
                } else {
                    missingRanges++;
                }

                List<Integer> legacyEnclosingRange = typedEnclosingRange(occurrence);
                if (!legacyEnclosingRange.isEmpty()) {
                    converted.clearEnclosingRange()
                            .addAllEnclosingRange(legacyEnclosingRange)
                            .clearTypedEnclosingRange();
                    typedEnclosingRangesConverted++;
                }

                convertedDocument.addOccurrences(converted);
            }
            convertedIndex.addDocuments(convertedDocument);
        }

        return new ConversionResult(
                convertedIndex.build(),
                occurrences,
                typedRangesConverted,
                typedEnclosingRangesConverted,
                legacyRangesRetained,
                missingRanges
        );
    }

    private static List<Integer> typedRange(Occurrence occurrence) {
        if (occurrence.hasSingleLineRange()) {
            var range = occurrence.getSingleLineRange();
            return List.of(range.getLine(), range.getStartCharacter(), range.getEndCharacter());
        }
        if (occurrence.hasMultiLineRange()) {
            var range = occurrence.getMultiLineRange();
            return List.of(
                    range.getStartLine(),
                    range.getStartCharacter(),
                    range.getEndLine(),
                    range.getEndCharacter()
            );
        }
        return List.of();
    }

    private static List<Integer> typedEnclosingRange(Occurrence occurrence) {
        if (occurrence.hasSingleLineEnclosingRange()) {
            var range = occurrence.getSingleLineEnclosingRange();
            return List.of(range.getLine(), range.getStartCharacter(), range.getEndCharacter());
        }
        if (occurrence.hasMultiLineEnclosingRange()) {
            var range = occurrence.getMultiLineEnclosingRange();
            return List.of(
                    range.getStartLine(),
                    range.getStartCharacter(),
                    range.getEndLine(),
                    range.getEndCharacter()
            );
        }
        return List.of();
    }

    private static void writeTransactionally(Index index, Path output) throws IOException {
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path partial = output.resolveSibling(output.getFileName() + ".partial");
        try (OutputStream stream = Files.newOutputStream(
                partial,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        )) {
            index.writeTo(stream);
        }
        try {
            Files.move(
                    partial,
                    output,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(partial, output, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void metric(String name, Object value) {
        System.out.println("METRIC\t" + name + "\t" + value);
    }

    record ConversionResult(
            Index index,
            int occurrences,
            int typedRangesConverted,
            int typedEnclosingRangesConverted,
            int legacyRangesRetained,
            int missingRanges) {
    }
}
