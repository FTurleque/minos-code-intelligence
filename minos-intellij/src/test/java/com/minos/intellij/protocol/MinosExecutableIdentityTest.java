package com.minos.intellij.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class MinosExecutableIdentityTest {

    @Test
    void identityChangesWhenTheBinaryAtTheSamePathIsReplaced(@TempDir Path temporary) throws Exception {
        Path executable = temporary.resolve("minos");
        Files.writeString(executable, "original-binary-contents");
        Files.setLastModifiedTime(executable, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        String before = MinosExecutableIdentity.describe(executable);

        Files.writeString(executable, "replaced-binary-contents-with-a-different-length");
        Files.setLastModifiedTime(executable, FileTime.from(Instant.parse("2026-06-01T00:00:00Z")));
        String after = MinosExecutableIdentity.describe(executable);

        assertNotEquals(before, after);
    }

    @Test
    void identityIsStableForAnUnchangedFile(@TempDir Path temporary) throws Exception {
        Path executable = temporary.resolve("minos");
        Files.writeString(executable, "stable-binary-contents");

        assertEquals(MinosExecutableIdentity.describe(executable), MinosExecutableIdentity.describe(executable));
    }

    @Test
    void unresolvedPathReturnsAStableSentinelInsteadOfThrowing(@TempDir Path temporary) {
        Path missing = temporary.resolve("does-not-exist");

        assertEquals("unresolved", MinosExecutableIdentity.describe(missing));
    }
}
