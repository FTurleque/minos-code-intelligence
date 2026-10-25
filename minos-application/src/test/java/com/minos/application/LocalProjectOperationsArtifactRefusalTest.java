package com.minos.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A SCIP artifact that is not a regular file is refused by its name, never by its absolute path (AUD-SEC-04). */
class LocalProjectOperationsArtifactRefusalTest {

    @TempDir
    Path temp;

    @Test
    void aMissingOrDirectoryArtifactIsRefusedByItsName() throws Exception {
        Path directory = Files.createDirectory(temp.resolve("index.scip"));
        for (Path refused : new Path[] {temp.resolve("missing.scip"), directory}) {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> LocalProjectOperations.requireRegularArtifact(refused));

            assertEquals("SCIP artifact must be an existing regular file: " + refused.getFileName(),
                    refusal.getMessage());
            assertFalse(refusal.getMessage().contains(temp.toString()), refusal.getMessage());
        }
    }

    @Test
    void aRegularArtifactIsReturnedAbsoluteAndNormalized() throws Exception {
        Path artifact = Files.writeString(temp.resolve("index.scip"), "x");

        assertEquals(artifact.toAbsolutePath().normalize(),
                LocalProjectOperations.requireRegularArtifact(temp.resolve("sub").resolve("..").resolve("index.scip")));
    }
}
