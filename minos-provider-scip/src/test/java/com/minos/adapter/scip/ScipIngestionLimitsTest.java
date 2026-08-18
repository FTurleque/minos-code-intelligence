package com.minos.adapter.scip;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.scip_code.scip.Document;
import org.scip_code.scip.Index;
import org.scip_code.scip.Occurrence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScipIngestionLimitsTest {

    @Test
    void rejectsArtifactBeforeProtobufParsingWhenByteBudgetIsExceeded(@TempDir Path temp) throws Exception {
        ScipIngestionLimits limits = new ScipIngestionLimits(8, 10, 10, 10, 10);
        Path index = temp.resolve("index.scip");
        Files.write(index, new byte[9]);

        assertThrows(IOException.class, () -> new ScipIndexReader(limits).read(index));
    }

    @Test
    void acceptsExactOccurrenceBoundaryAndRejectsBoundaryPlusOne() throws Exception {
        ScipIngestionLimits limits = new ScipIngestionLimits(1024, 10, 10, 2, 10);
        Index exact = Index.newBuilder()
                .addDocuments(Document.newBuilder()
                        .setRelativePath("A.java")
                        .addOccurrences(Occurrence.newBuilder())
                        .addOccurrences(Occurrence.newBuilder()))
                .build();
        Index overflow = exact.toBuilder()
                .addDocuments(Document.newBuilder()
                        .setRelativePath("B.java")
                        .addOccurrences(Occurrence.newBuilder()))
                .build();

        assertDoesNotThrow(() -> limits.validate(exact));
        assertThrows(IOException.class, () -> limits.validate(overflow));
    }

    @Test
    void decodeHeapBudgetRejectsCardinalityAmplificationBeforeFullParse() {
        ScipIngestionLimits limits = ScipIngestionLimits.DEFAULT;
        ScipIngestionLimits.PreflightMetrics hostile = new ScipIngestionLimits.PreflightMetrics(
                50_000L,
                500_000L,
                8_000_000L,
                1_000_000L,
                128L * 1024L * 1024L);

        IOException failure = assertThrows(
                IOException.class,
                () -> limits.enforceDecodeHeapBudget(
                        hostile,
                        128L * 1024L * 1024L,
                        1536L * 1024L * 1024L));

        assertTrue(failure.getMessage().contains("safe decode heap budget"));
    }

    @Test
    void decodeHeapBudgetAllowsSmallValidatedIndex() {
        ScipIngestionLimits limits = ScipIngestionLimits.DEFAULT;
        ScipIngestionLimits.PreflightMetrics small = new ScipIngestionLimits.PreflightMetrics(
                10L,
                100L,
                1_000L,
                100L,
                1024L * 1024L);

        assertDoesNotThrow(() -> limits.enforceDecodeHeapBudget(
                small,
                2L * 1024L * 1024L,
                1024L * 1024L * 1024L));
    }
}
