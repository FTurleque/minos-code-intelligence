package com.minos.orchestration;

import com.minos.orchestration.ArtifactConfinement.Escape;
import com.minos.orchestration.ArtifactConfinement.Reason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** La décision unique de confinement d'un artefact au répertoire de run (Q5), cas par cas. */
class ArtifactConfinementTest {

    @Test
    void aRegularFileUnderTheRunDirectoryIsInside(@TempDir Path temp) throws IOException {
        Path runDirectory = Files.createDirectories(temp.resolve("run"));
        Path artifact = write(runDirectory.resolve("provider").resolve("scope").resolve("index.scip"));

        assertDoesNotThrow(() -> ArtifactConfinement.requireInside(runDirectory, artifact));
    }

    @Test
    void aPathThatNormalizesOutsideTheRunDirectoryIsOutside(@TempDir Path temp) throws IOException {
        Path runDirectory = Files.createDirectories(temp.resolve("run"));
        Path outside = write(temp.resolve("outside").resolve("index.scip"));
        Path viaParent = runDirectory.resolve("..").resolve("outside").resolve("index.scip");

        assertEquals(Reason.OUTSIDE, reasonOf(runDirectory, viaParent));
        assertEquals(Reason.OUTSIDE, reasonOf(runDirectory, outside));
    }

    @Test
    void aSiblingDirectoryWhoseNameOnlyStartsLikeTheRunDirectoryIsOutside(@TempDir Path temp) throws IOException {
        // A textual prefix is not containment: "run-evil" starts with "run".
        Path runDirectory = Files.createDirectories(temp.resolve("run"));
        Path sibling = write(temp.resolve("run-evil").resolve("index.scip"));

        assertEquals(Reason.OUTSIDE, reasonOf(runDirectory, sibling));
    }

    @Test
    void aMissingFileIsMissingAndADirectoryIsRefused(@TempDir Path temp) throws IOException {
        Path runDirectory = Files.createDirectories(temp.resolve("run"));
        Path directory = Files.createDirectories(runDirectory.resolve("a-directory"));

        assertEquals(Reason.MISSING, reasonOf(runDirectory, runDirectory.resolve("never-written.scip")));
        // Whatever the platform calls it (a special object, or a file that cannot be opened), it is refused.
        assertThrows(Escape.class, () -> ArtifactConfinement.requireInside(runDirectory, directory));
    }

    @Test
    void aMissingRunDirectoryCannotBeResolved(@TempDir Path temp) throws IOException {
        Path artifact = write(temp.resolve("somewhere").resolve("index.scip"));

        assertEquals(Reason.UNRESOLVABLE, reasonOf(temp.resolve("no-such-run"), artifact));
    }

    @Test
    void aLinkIsRefusedEvenWhenItsTargetFallsBackInsideTheRunDirectory(@TempDir Path temp) throws IOException {
        assumeTrue(canLink(temp), "symbolic links are not available here");
        Path runDirectory = Files.createDirectories(temp.resolve("run"));
        Path real = write(runDirectory.resolve("real.scip"));
        Path link = Files.createSymbolicLink(runDirectory.resolve("index.scip"), real);

        assertEquals(Reason.LINKED, reasonOf(runDirectory, link));
    }

    @Test
    void anEscapeNeverCarriesAPath(@TempDir Path temp) throws IOException {
        Path runDirectory = Files.createDirectories(temp.resolve("run"));
        Path outside = write(temp.resolve("outside").resolve("index.scip"));

        Escape escape = assertThrows(Escape.class, () -> ArtifactConfinement.requireInside(runDirectory, outside));

        assertFalse(escape.getMessage().contains(temp.getFileName().toString()), escape.getMessage());
        assertTrue(escape.getMessage().startsWith("artifact "), escape.getMessage());
    }

    private static Reason reasonOf(Path runDirectory, Path artifact) {
        return assertThrows(Escape.class, () -> ArtifactConfinement.requireInside(runDirectory, artifact)).reason();
    }

    private static Path write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "scip:content");
    }

    private static boolean canLink(Path directory) {
        try {
            Path target = Files.createDirectories(directory.resolve("probe-target"));
            Files.createSymbolicLink(directory.resolve("probe-link"), target);
            return true;
        } catch (IOException | UnsupportedOperationException unavailable) {
            return false;
        }
    }
}
