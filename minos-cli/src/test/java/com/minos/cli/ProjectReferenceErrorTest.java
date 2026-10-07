package com.minos.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-C01 sur la CLI : une référence de type chemin donne une erreur actionnable (et plus le seul nom de la
 * classe d'exception), sans recopier le chemin.
 */
class ProjectReferenceErrorTest {

    @TempDir
    Path temp;

    @Test
    void aPathShapedReferenceIsReportedAsUnknownWithTheWayToFixIt() throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));
        for (String reference : new String[]{"C:\\Users\\x\\proj", "/home/x/proj", "\\\\srv\\share\\p"}) {
            StringBuilder output = new StringBuilder();
            StringBuilder error = new StringBuilder();

            int exit = MinosLauncher.run(home, new String[]{"index-status", reference}, output, error);

            String context = reference + " -> " + error;
            assertEquals(1, exit, context);
            assertTrue(error.toString().contains("unknown project"), context);
            assertTrue(error.toString().contains("project name or UUID"), context);
            assertFalse(error.toString().contains("ResolutionException"), context);
            assertFalse(error.toString().contains("x\\proj") || error.toString().contains("x/proj"), context);
            assertFalse(error.toString().contains("srv"), context);
            assertNotEquals("error: index-status failed: ResolutionException\n", error.toString());
        }
    }

    @Test
    void aPlainUnknownNameKeepsTheMessageItHadBeforeThisChange() throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();

        int exit = MinosLauncher.run(home, new String[]{"index-status", "demo"}, output, error);

        assertEquals(1, exit);
        assertTrue(error.toString().contains("unknown project: demo"), error.toString());
    }
}
