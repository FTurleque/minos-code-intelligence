package com.minos.security;

import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscoveryService;
import com.minos.incremental.ProjectFingerprint;
import com.minos.incremental.ProjectFingerprintService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectTreeConfinementTest {

    @Test
    void fingerprintDoesNotTraverseAWindowsJunctionOutsideTheProject(@TempDir Path temporaryDirectory)
            throws Exception {
        Assumptions.assumeTrue(isWindows(), "NTFS junctions are a Windows-only reparse point");
        Path project = Files.createDirectories(temporaryDirectory.resolve("project"));
        Files.writeString(project.resolve("inside.txt"), "inside");
        Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret-v1");
        Path junction = project.resolve("outside-junction");
        createJunction(junction, outside);
        assertTrue(Files.isDirectory(junction));
        assertFalse(Files.isSymbolicLink(junction));

        ProjectFingerprintService service = new ProjectFingerprintService();
        ProjectFingerprint before = service.capture(project);
        Files.writeString(outside.resolve("secret.txt"), "secret-v2");
        ProjectFingerprint after = service.capture(project);

        assertEquals(before, after, "external junction-target changes must not affect the project fingerprint");
        assertTrue(after.files().stream().noneMatch(file -> file.relativePath().startsWith("outside-junction/")));
    }

    @Test
    void discoveryDoesNotTreatAWindowsJunctionTargetAsProjectStructure(@TempDir Path temporaryDirectory)
            throws Exception {
        Assumptions.assumeTrue(isWindows(), "NTFS junctions are a Windows-only reparse point");
        Path project = Files.createDirectories(temporaryDirectory.resolve("project"));
        Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
        Files.writeString(outside.resolve("package.json"), "{}");
        Files.createDirectories(outside.resolve("src"));
        Files.writeString(outside.resolve("src/app.ts"), "export const value = 1;");
        Path junction = project.resolve("outside-module");
        createJunction(junction, outside);
        assertTrue(Files.isDirectory(junction));
        assertFalse(Files.isSymbolicLink(junction));

        ProjectDiscovery discovery = new ProjectDiscoveryService().discover(project);

        assertTrue(discovery.buildSystems().isEmpty());
        assertTrue(discovery.languages().isEmpty());
        assertEquals(1, discovery.modules().size());
        assertTrue(discovery.modules().getFirst().relativePath().toString().isEmpty());
        assertTrue(discovery.modules().getFirst().sourceRoots().isEmpty());
    }

    private static void createJunction(Path junction, Path target) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", junction.toString(), target.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("mklink /J failed (exit=" + exit + "): " + output);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
