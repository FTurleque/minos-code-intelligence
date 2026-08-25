package com.minos.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoundedFileDigestTest {
    @Test
    void hashesExactlyAtTheConfiguredLimit(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("payload.bin");
        Files.write(file, new byte[]{1, 2, 3});
        assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
                BoundedFileDigest.sha256Exact(file, 3, "payload"));
    }

    @Test
    void rejectsOversizedOrEmptyPayloads(@TempDir Path temp) throws Exception {
        Path oversized = temp.resolve("oversized.bin");
        Files.write(oversized, new byte[]{1, 2, 3, 4});
        assertThrows(IOException.class, () -> BoundedFileDigest.sha256Exact(oversized, 3, "payload"));
        Path empty = temp.resolve("empty.bin");
        Files.createFile(empty);
        assertThrows(IOException.class, () -> BoundedFileDigest.sha256Exact(empty, 3, "payload"));
    }

    @Test
    void rejectsSymbolicLinksWhenSupported(@TempDir Path temp) throws Exception {
        Path target = temp.resolve("target.bin");
        Files.write(target, new byte[]{1});
        Path link = temp.resolve("link.bin");
        try {
            Files.createSymbolicLink(link, target.getFileName());
        } catch (UnsupportedOperationException | IOException exception) {
            return;
        }
        assertThrows(IOException.class, () -> BoundedFileDigest.sha256Exact(link, 3, "payload"));
    }
}
