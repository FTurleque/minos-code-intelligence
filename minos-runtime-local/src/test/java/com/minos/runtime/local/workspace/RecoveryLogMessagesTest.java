package com.minos.runtime.local.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.AccessDeniedException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RecoveryLogMessagesTest {

    @Test
    void theMessageNamesTheRelativePathAndTheExceptionClassOnly(@TempDir Path home) {
        Path runsRoot = home.resolve("runs");
        Path target = runsRoot.resolve("run-1").resolve("out");
        // AccessDeniedException's message IS the absolute path: it must never reach the journal.
        Exception failure = new AccessDeniedException(target.toAbsolutePath().toString());

        String message = RecoveryLogMessages.of("reclaim run directory", runsRoot, target, failure);

        assertEquals("MINOS could not reclaim run directory 'run-1/out': AccessDeniedException", message);
        assertFalse(message.contains(home.toString()), message);
        assertFalse(message.contains(System.getProperty("user.name")), message);
    }

    @Test
    void aPathOutsideTheManagedRootIsNotShown(@TempDir Path home) {
        Path runsRoot = home.resolve("runs");
        Path elsewhere = home.resolve("elsewhere").resolve("secret");

        String message = RecoveryLogMessages.of("reclaim run directory", runsRoot, elsewhere, new java.io.IOException());

        assertEquals("MINOS could not reclaim run directory '<outside the managed root>': IOException", message);
        assertFalse(message.contains("secret"), message);
    }

    @Test
    void theRootItselfIsNotShownEither(@TempDir Path home) {
        String message = RecoveryLogMessages.of("enumerate", home, home, new java.io.IOException());

        assertFalse(message.contains(home.toString()), message);
    }

    @Test
    void aFailureWithoutAPathNamesOnlyTheClass() {
        assertEquals("MINOS could not enumerate the run quarantine: AccessDeniedException",
                RecoveryLogMessages.of("enumerate the run quarantine", new AccessDeniedException("/home/alice/runs")));
    }
}
