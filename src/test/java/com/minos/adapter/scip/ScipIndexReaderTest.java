package com.minos.adapter.scip;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.scip_code.scip.Index;
import org.scip_code.scip.Metadata;
import org.scip_code.scip.ToolInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        assertThrows(IOException.class, () -> new ScipIndexReader().read(missing));
    }
}
