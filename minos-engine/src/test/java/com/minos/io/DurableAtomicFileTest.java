package com.minos.io;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurableAtomicFileTest {

    @AfterEach
    void resetCapabilityProbe() {
        PrivateLocalStorage.resetCapabilityProbeForTesting();
    }

    @Test
    void ensureDirectoryFailsClosedWhenTheFilesystemCannotEnforcePrivacy(@TempDir Path root) {
        // DurableAtomicFile delegates directory privacy entirely to PrivateLocalStorage: a
        // control-plane directory it creates must never be usable when ownership cannot be
        // guaranteed for it, on the same terms as every other private-storage consumer.
        PrivateLocalStorage.useForTesting(FakeCapabilityProbe.unsupported());
        Path directory = root.resolve("hosted-control-plane");

        assertThrows(IOException.class, () -> DurableAtomicFile.ensureDirectory(directory, "control-plane"));
        assertFalse(Files.isDirectory(directory), "a directory that could not be made private must not remain");
    }

    @Test
    void directorySyncFailureAfterAtomicMoveIsExplicitlyCommitUncertain(@TempDir Path root) throws Exception {
        Path source = Files.writeString(root.resolve("next.tmp"), "new-value");
        Path target = Files.writeString(root.resolve("state.properties"), "old-value");

        CommitUncertainException failure = assertThrows(
                CommitUncertainException.class,
                () -> DurableAtomicFile.move(
                        source,
                        target,
                        true,
                        "test replacement",
                        directory -> { throw new IOException("synthetic directory fsync failure"); }));

        assertTrue(failure.getMessage().contains("committed"));
        assertEquals("new-value", Files.readString(target));
        assertFalse(Files.exists(source));
    }
}
