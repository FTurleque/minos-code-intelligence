package com.minos.program.analysis;

import com.minos.io.Sha256;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two fingerprints of a project source open its leaf without following a link (AUD-SEC-03): a source replaced by
 * a link after its path was resolved is refused instead of read, and the ordinary fingerprint is unchanged.
 */
class SourceFingerprintLeafNoFollowTest {

    private static final byte[] CONTENT = "class A { int answer() { return 42; } }\n".getBytes(StandardCharsets.UTF_8);

    private static String expectedSha256() {
        MessageDigest digest = Sha256.newDigest();
        digest.update(CONTENT);
        return Sha256.hex(digest);
    }

    private static Path link(Path directory, Path target) throws IOException {
        Path link = directory.resolve("Linked.java");
        try {
            Files.createSymbolicLink(link, target.toAbsolutePath());
        } catch (UnsupportedOperationException | SecurityException | IOException exception) {
            Assumptions.abort("symbolic links are unavailable: " + exception.getClass().getSimpleName());
        }
        return link;
    }

    @Test
    void anOrdinarySourceKeepsItsFingerprint(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("A.java");
        Files.write(source, CONTENT);

        assertEquals(expectedSha256(),
                FingerprintConstrainedJavaProgramGraphProvider.sha256Exact(source, CONTENT.length, "Java snapshot source"));

        MessageDigest digest = Sha256.newDigest();
        JavaSourceWorkspace.updateBounded(digest, source, 1024, "Java source fingerprint");
        assertEquals(expectedSha256(), Sha256.hex(digest));
    }

    @Test
    void aSourceTurnedIntoALinkIsRefusedByTheSnapshotComparison(@TempDir Path directory) throws Exception {
        Path target = directory.resolve("Target.java");
        Files.write(target, CONTENT);
        Path link = link(directory, target);

        assertThrows(IOException.class, () -> FingerprintConstrainedJavaProgramGraphProvider.sha256Exact(
                link, CONTENT.length, "Java snapshot source"));
        assertEquals(new String(CONTENT, StandardCharsets.UTF_8), Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void aSourceTurnedIntoALinkIsRefusedByTheStateFingerprint(@TempDir Path directory) throws Exception {
        Path target = directory.resolve("Target.java");
        Files.write(target, CONTENT);
        Path link = link(directory, target);

        assertThrows(IOException.class, () -> JavaSourceWorkspace.updateBounded(
                Sha256.newDigest(), link, 1024, "Java source fingerprint"));
        assertEquals(new String(CONTENT, StandardCharsets.UTF_8), Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void aSourceWhichGrowsWhileBeingFingerprintedKeepsItsMessage(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("A.java");
        Files.write(source, CONTENT);

        IOException grew = assertThrows(IOException.class, () -> FingerprintConstrainedJavaProgramGraphProvider
                .sha256Exact(source, CONTENT.length - 1L, "Java snapshot source"));
        assertTrue(grew.getMessage().contains("grew while being fingerprinted"), grew.getMessage());

        IOException shrank = assertThrows(IOException.class, () -> FingerprintConstrainedJavaProgramGraphProvider
                .sha256Exact(source, CONTENT.length + 1L, "Java snapshot source"));
        assertTrue(shrank.getMessage().contains("changed size while being fingerprinted"), shrank.getMessage());
    }

    @Test
    void aSourceBeyondTheStateFingerprintBoundIsRefused(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("A.java");
        Files.write(source, CONTENT);

        assertThrows(IOException.class, () -> JavaSourceWorkspace.updateBounded(
                Sha256.newDigest(), source, CONTENT.length - 1L, "Java source fingerprint"));
    }

    @Test
    void aDirectoryIsNotASource(@TempDir Path directory) {
        assertThrows(IOException.class, () -> FingerprintConstrainedJavaProgramGraphProvider.sha256Exact(
                directory, 0L, "Java snapshot source"));
    }
}
