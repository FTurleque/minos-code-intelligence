package com.minos.io;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileTreeOperationsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void deletesNestedTreesWithoutFollowingDirectorySymlinks() throws IOException {
        Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
        Path evidence = Files.writeString(outside.resolve("evidence.txt"), "preserved");
        Path target = Files.createDirectories(temporaryDirectory.resolve("target/nested"));
        Files.writeString(target.resolve("inside.txt"), "deleted");
        Path link = temporaryDirectory.resolve("target/outside-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException exception) {
            link = null;
        }

        FileTreeOperations.deleteRecursively(temporaryDirectory.resolve("target"));

        assertFalse(Files.exists(temporaryDirectory.resolve("target")));
        assertTrue(Files.isRegularFile(evidence));
        if (link != null) assertFalse(Files.exists(link));
    }

    @Test
    void deletesAWindowsJunctionEntryWithoutWalkingThroughToItsTarget() throws Exception {
        Assumptions.assumeTrue(isWindows(), "NTFS junctions are a Windows-only reparse point");
        Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
        Path evidence = Files.writeString(outside.resolve("evidence.txt"), "preserved");
        Path target = Files.createDirectories(temporaryDirectory.resolve("target/nested"));
        Files.writeString(target.resolve("inside.txt"), "deleted");
        Path junction = temporaryDirectory.resolve("target/outside-junction");
        createJunction(junction, outside);
        assertTrue(Files.isDirectory(junction), "fixture must prove the junction resolves as a directory");
        assertFalse(Files.isSymbolicLink(junction),
                "fixture must prove a junction is not reported as a symbolic link");

        FileTreeOperations.deleteRecursively(temporaryDirectory.resolve("target"));

        assertFalse(Files.exists(temporaryDirectory.resolve("target")));
        assertFalse(Files.exists(junction, java.nio.file.LinkOption.NOFOLLOW_LINKS),
                "the junction entry itself must be removed");
        assertTrue(Files.isRegularFile(evidence),
                "deleting a junction must never delete anything at its target");
    }

    private static void createJunction(Path link, Path target) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0) throw new IOException("mklink /J failed (exit=" + exit + "): " + output);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
