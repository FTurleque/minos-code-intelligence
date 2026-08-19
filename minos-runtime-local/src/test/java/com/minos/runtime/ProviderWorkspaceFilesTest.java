package com.minos.runtime;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderWorkspaceFilesTest {

    @Test
    void deleteTreeRemovesAWorkspaceIncludingOrdinaryNestedContent(@TempDir Path home) throws Exception {
        Path allowedRoot = Files.createDirectories(home.resolve("workspaces"));
        Path workspace = Files.createDirectories(allowedRoot.resolve("provider-1"));
        Path nested = Files.createDirectories(workspace.resolve("nested"));
        Files.writeString(nested.resolve("file.txt"), "provider output");

        ProviderWorkspaceFiles.deleteTree(allowedRoot, workspace, "test workspace");

        assertFalse(Files.exists(workspace));
        assertTrue(Files.exists(allowedRoot), "only the workspace itself is removed, not its parent root");
    }

    @Test
    void deleteTreeRefusesToDeleteOutsideItsAllowedRoot(@TempDir Path home) throws Exception {
        Path allowedRoot = Files.createDirectories(home.resolve("workspaces"));
        Path outside = Files.createDirectories(home.resolve("outside"));

        assertThrows(IOException.class,
                () -> ProviderWorkspaceFiles.deleteTree(allowedRoot, outside, "test workspace"));
        assertThrows(IOException.class,
                () -> ProviderWorkspaceFiles.deleteTree(allowedRoot, allowedRoot, "test workspace"));
        assertTrue(Files.exists(outside));
    }

    @Test
    void deleteTreeQuarantinesAProviderPlantedJunctionInsteadOfWalkingThroughToItsTarget(
            @TempDir Path home) throws Exception {
        Assumptions.assumeTrue(CommandLocator.isWindows(), "NTFS junctions are a Windows-only reparse point");
        Path outside = Files.createDirectories(home.resolve("outside"));
        Path evidence = Files.writeString(outside.resolve("evidence.txt"), "preserved");
        Path allowedRoot = Files.createDirectories(home.resolve("workspaces"));
        Path workspace = Files.createDirectories(allowedRoot.resolve("provider-1"));
        Path junction = workspace.resolve("outside-junction");
        createJunction(junction, outside);

        ProviderWorkspaceFiles.deleteTree(allowedRoot, workspace, "test workspace");

        assertFalse(Files.exists(workspace));
        assertFalse(Files.exists(junction, LinkOption.NOFOLLOW_LINKS), "the junction entry itself must be removed");
        assertTrue(Files.isRegularFile(evidence),
                "deleting a provider workspace must never delete anything at a planted junction's target");
    }

    private static void createJunction(Path link, Path target) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0) throw new IOException("mklink /J failed (exit=" + exit + "): " + output);
    }
}
