package com.minos.runtime.local.process;

import com.minos.io.PrivateLocalStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The diagnostic files of a provider run are created new and hardened (AUD-SEC-03): a name that reappears between the
 * deletion of the old file and the creation of the new one is refused, never adopted, and a link is never followed.
 */
class BoundedProcessOutputOpeningTest {

    @Test
    void aFreeNameIsCreatedPrivateAndWritten(@TempDir Path run) throws Exception {
        Path stdout = run.resolve("provider.stdout.log");

        try (OutputStream output = BoundedProcessOutput.openTarget(stdout)) {
            output.write("line".getBytes(StandardCharsets.UTF_8));
        }

        assertEquals("line", Files.readString(stdout));
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(stdout));
    }

    @Test
    void anOldFileUnderTheNameIsReplacedByANewOne(@TempDir Path run) throws Exception {
        Path stdout = Files.writeString(run.resolve("provider.stdout.log"), "previous run", StandardCharsets.UTF_8);

        try (OutputStream output = BoundedProcessOutput.openTarget(stdout)) {
            output.write("now".getBytes(StandardCharsets.UTF_8));
        }

        assertEquals("now", Files.readString(stdout));
    }

    @Test
    void aFileWhichReappearedUnderTheNameIsNotAdopted(@TempDir Path run) throws Exception {
        Path stdout = run.resolve("provider.stdout.log");

        FileAlreadyExistsException refusal = assertThrows(FileAlreadyExistsException.class,
                () -> BoundedProcessOutput.openTarget(stdout, () -> {
                    try {
                        Files.writeString(stdout, "planted by a provider", StandardCharsets.UTF_8);
                    } catch (IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                }));

        assertFalse(String.valueOf(refusal.getMessage()).contains(run.toString()), refusal.getMessage());
        assertEquals("planted by a provider", Files.readString(stdout));
    }

    @Test
    void aLinkWhichReappearedUnderTheNameIsNotFollowed(@TempDir Path run) throws Exception {
        Path stdout = run.resolve("provider.stdout.log");
        Path sentinel = Files.writeString(run.resolve("sentinel.txt"), "untouched", StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> BoundedProcessOutput.openTarget(stdout, () -> {
            try {
                Files.createSymbolicLink(stdout, sentinel);
            } catch (IOException | UnsupportedOperationException _) {
                assumeTrue(false, "this platform/account cannot create symbolic links");
            }
        }));

        assertEquals("untouched", Files.readString(sentinel));
    }

    @Test
    void aNullTargetDiscardsTheOutput() throws Exception {
        try (OutputStream output = BoundedProcessOutput.openTarget(null)) {
            output.write(1);
        }
    }
}
