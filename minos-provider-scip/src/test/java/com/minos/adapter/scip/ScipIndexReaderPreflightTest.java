package com.minos.adapter.scip;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.scip_code.scip.Document;
import org.scip_code.scip.Index;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScipIndexReaderPreflightTest {

    @TempDir
    Path temporary;

    @Test
    void rejectsDocumentOverflowDuringWirePreflight() throws Exception {
        Path artifact = temporary.resolve("overflow.scip");
        Index index = Index.newBuilder()
                .addDocuments(Document.newBuilder().setRelativePath("a.java"))
                .addDocuments(Document.newBuilder().setRelativePath("b.java"))
                .build();
        try (OutputStream output = Files.newOutputStream(artifact)) {
            index.writeTo(output);
        }
        ScipIngestionLimits limits = new ScipIngestionLimits(
                1024 * 1024L, 1L, 100L, 100L, 100L);
        IOException failure = assertThrows(IOException.class, () -> new ScipIndexReader(limits).read(artifact));
        assertTrue(failure.getMessage().contains("documents limit"));
    }
}
