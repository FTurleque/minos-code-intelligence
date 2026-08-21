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

    @Test
    void discoveryDoesNotAcceptASymbolicLinkAsABuildMarker(@TempDir Path temporaryDirectory)
            throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("project"));
        Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
        Path outsidePom = Files.writeString(outside.resolve("pom.xml"), "<project/>\n");
        createSymbolicLinkOrSkip(project.resolve("pom.xml"), outsidePom);

        ProjectDiscovery discovery = new ProjectDiscoveryService().discover(project);

        assertTrue(discovery.buildSystems().isEmpty(),
                "a build marker must be a physical regular file inside the project boundary");
    }

    @Test
    void discoveryDoesNotAcceptASymbolicLinkAsAnExtensionBuildMarker(@TempDir Path temporaryDirectory)
            throws Exception {
        Path project = Files.createDirectories(temporaryDirectory.resolve("project"));
        Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
        Path outsideProject = Files.writeString(outside.resolve("outside.csproj"), "<Project/>\n");
        createSymbolicLinkOrSkip(project.resolve("linked.csproj"), outsideProject);

        ProjectDiscovery discovery = new ProjectDiscoveryService().discover(project);

        assertTrue(discovery.buildSystems().isEmpty(),
                "extension-based build markers must not follow symbolic links outside the project");
    }

    @Test
    void discoveryDoesNotAcceptAWindowsJunctionAsAConventionalSourceRoot(@TempDir Path temporaryDirectory)
            throws Exception {
        Assumptions.assumeTrue(isWindows(), "NTFS junctions are a Windows-only reparse point");
        Path project = Files.createDirectories(temporaryDirectory.resolve("project"));
        Files.writeString(project.resolve("package.json"), "{}");
        Path outsideSource = Files.createDirectories(temporaryDirectory.resolve("outside-source"));
        Files.writeString(outsideSource.resolve("app.ts"), "export const value = 1;\n");
        Path sourceJunction = project.resolve("src");
        createJunction(sourceJunction, outsideSource);
        assertTrue(Files.isDirectory(sourceJunction));
        assertFalse(Files.isSymbolicLink(sourceJunction));

        ProjectDiscovery discovery = new ProjectDiscoveryService().discover(project);

        assertTrue(discovery.languages().isEmpty(),
                "files behind a source-root junction must not influence language discovery");
        assertTrue(discovery.modules().stream().flatMap(module -> module.sourceRoots().stream()).findAny().isEmpty(),
                "a conventional source root must be a physical directory inside the project");
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

    private static void createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "symbolic-link creation is unavailable: " + unavailable.getMessage());
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
