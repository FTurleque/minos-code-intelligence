package com.minos.adapter.scip.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.scip_code.scip.Index;
import org.scip_code.scip.Metadata;
import org.scip_code.scip.ToolInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScipIndexReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void readsBinaryScipIndex() throws IOException {
        Path indexFile = tempDir.resolve("index.scip");

        Index expected = Index.newBuilder()
                .setMetadata(Metadata.newBuilder()
                        .setProjectRoot("file:///fixture")
                        .setToolInfo(ToolInfo.newBuilder()
                                .setName("fixture-indexer")
                                .setVersion("1.0")))
                .build();

        try (var output = Files.newOutputStream(indexFile)) {
            expected.writeTo(output);
        }

        Index actual = new ScipIndexReader().read(indexFile);

        assertEquals("fixture-indexer", actual.getMetadata().getToolInfo().getName());
        assertEquals("1.0", actual.getMetadata().getToolInfo().getVersion());
    }

    @Test
    void rejectsMissingIndexFile() {
        Path missing = tempDir.resolve("missing.scip");

        IOException refusal = assertThrows(IOException.class, () -> new ScipIndexReader().read(missing));

        // AUD-SEC-04: the index is named by its file name, never by its absolute path.
        assertEquals("SCIP index does not exist or is not a regular file: missing.scip", refusal.getMessage());
        assertFalse(refusal.getMessage().contains(tempDir.toString()), refusal.getMessage());
    }

    @Test
    void rejectsAnEmptyIndexFileByItsName() throws IOException {
        Path empty = Files.createFile(tempDir.resolve("empty.scip"));

        IOException refusal = assertThrows(IOException.class, () -> new ScipIndexReader().read(empty));

        assertEquals("SCIP index is empty: empty.scip", refusal.getMessage());
        assertFalse(refusal.getMessage().contains(tempDir.toString()), refusal.getMessage());
    }
}
