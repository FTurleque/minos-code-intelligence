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

        ProjectDiscoveryService discovery = new ProjectDiscoveryService();

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> discovery.discover(file));

        assertEquals("projectRoot must be an existing directory: not-a-project.txt", refusal.getMessage());
        assertFalse(refusal.getMessage().contains(temp.toString()), refusal.getMessage());
    }

    @Test
    void fingerprintRefusesAMissingRootByItsName() {
        Path missing = temp.resolve("missing-project");

        ProjectFingerprintService fingerprints = new ProjectFingerprintService();
        Path scope = Path.of("");

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> fingerprints.captureScope(missing, scope));

        assertEquals("projectRoot must be an existing directory: missing-project", refusal.getMessage());
        assertFalse(refusal.getMessage().contains(temp.toString()), refusal.getMessage());
    }
}
