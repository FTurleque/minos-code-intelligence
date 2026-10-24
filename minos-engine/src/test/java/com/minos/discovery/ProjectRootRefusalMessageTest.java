package com.minos.discovery;

import com.minos.incremental.ProjectFingerprintService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A project root that is not a directory is refused by its name, never by its absolute path (AUD-SEC-04). */
class ProjectRootRefusalMessageTest {

    @TempDir
    Path temp;

    @Test
    void discoveryRefusesARegularFileByItsName() throws Exception {
        Path file = Files.writeString(temp.resolve("not-a-project.txt"), "x");

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> new ProjectDiscoveryService().discover(file));

        assertEquals("projectRoot must be an existing directory: not-a-project.txt", refusal.getMessage());
        assertFalse(refusal.getMessage().contains(temp.toString()), refusal.getMessage());
    }

    @Test
    void fingerprintRefusesAMissingRootByItsName() {
        Path missing = temp.resolve("missing-project");

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> new ProjectFingerprintService().captureScope(missing, Path.of("")));

        assertEquals("projectRoot must be an existing directory: missing-project", refusal.getMessage());
        assertFalse(refusal.getMessage().contains(temp.toString()), refusal.getMessage());
    }
}
