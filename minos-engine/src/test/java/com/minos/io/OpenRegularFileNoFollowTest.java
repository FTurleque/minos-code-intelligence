package com.minos.io;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The single-open read: a leaf replaced by a link after the type look is refused by the open itself.
 * The replacement is made at the exact instant between the look and the open, by the test seam, not
 * by racing a timer.
 */
class OpenRegularFileNoFollowTest {

    @AfterEach
    void clearSeam() {
        ConfinedFileOpener.beforeOpenForTests = () -> { };
    }

    @Test
    void readsARegularFile(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("data.txt"), "payload", StandardCharsets.UTF_8);
        try (InputStream input = ConfinedFileOpener.openRegularFileNoFollow(file)) {
            assertEquals("payload", new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void anAbsentFileIsReportedAsAbsent(@TempDir Path root) {
        assertThrows(NoSuchFileException.class,
                () -> ConfinedFileOpener.openRegularFileNoFollow(root.resolve("absent.txt")));
    }

    @Test
    void aDirectoryIsNotAFile(@TempDir Path root) {
        assertThrows(ConfinedFileOpener.ConfinementException.class,
                () -> ConfinedFileOpener.openRegularFileNoFollow(root));
    }

    @Test
    void aLinkLeafIsRefusedWithoutBeingFollowed(@TempDir Path root) throws Exception {
        Path secret = Files.writeString(root.resolve("secret.txt"), "SECRET", StandardCharsets.UTF_8);
        Path link = root.resolve("link.txt");
        assumeTrue(canLink(link, secret), "this platform/account cannot create symbolic links");

        ConfinedFileOpener.ConfinementException failure = assertThrows(
                ConfinedFileOpener.ConfinementException.class,
                () -> ConfinedFileOpener.openRegularFileNoFollow(link));
        assertFalse(String.valueOf(failure.getMessage()).contains(root.toString()),
                "the refusal must not carry the path");
    }

    @Test
    void aLeafSwappedForALinkAfterTheLookIsRefusedNotFollowed(@TempDir Path root) throws Exception {
        Path file = Files.writeString(root.resolve("envelope.tsv"), "legitimate", StandardCharsets.UTF_8);
        Path secret = Files.writeString(root.resolve("secret.txt"), "SECRET", StandardCharsets.UTF_8);
        Path probe = root.resolve("probe.lnk");
        assumeTrue(canLink(probe, secret), "this platform/account cannot create symbolic links");
        Files.delete(probe);

        ConfinedFileOpener.beforeOpenForTests = () -> {
            try {
                Files.delete(file);
                Files.createSymbolicLink(file, secret);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        };

        IOException failure = assertThrows(IOException.class, () -> assertNothingExposed(file));
        assertFalse(String.valueOf(failure.getMessage()).contains(root.toString()));
    }

    private static void assertNothingExposed(Path file) throws IOException {
        try (InputStream input = ConfinedFileOpener.openRegularFileNoFollow(file)) {
            String read = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(read.isEmpty(), "the swapped-in link was followed and exposed: " + read.length());
        }
    }

    private static boolean canLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException | IOException unsupported) {
            return false;
        }
    }
}
