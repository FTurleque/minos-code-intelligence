package com.minos.runtime;

import com.minos.source.SourceBudgetPolicy;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderWorkspaceFilesTest {

    @Test
    void copyWorkspaceCopiesOrdinaryContentThroughTheConfinedReadBoundary(@TempDir Path home) throws Exception {
        Path source = Files.createDirectories(home.resolve("source"));
        Files.createDirectories(source.resolve("src"));
        Files.writeString(source.resolve("src/Main.java"), "class Main {}\n");
        Path target = home.resolve("copy");

        ProviderWorkspaceFiles.copyWorkspace(
                source, target, new SourceBudgetPolicy(100, 1024 * 1024), "test workspace");

        assertEquals("class Main {}\n", Files.readString(target.resolve("src/Main.java")));
    }

    @Test
    void copyWorkspaceRejectsASymbolicLinkInsteadOfFollowingIt(@TempDir Path home) throws Exception {
        Path source = Files.createDirectories(home.resolve("source"));
        Path outside = Files.writeString(home.resolve("outside.txt"), "outside");
        Path link = source.resolve("linked.txt");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "symbolic-link creation is unavailable: " + unavailable.getMessage());
        }

        IOException failure = assertThrows(IOException.class, () -> ProviderWorkspaceFiles.copyWorkspace(
                source, home.resolve("copy"), new SourceBudgetPolicy(100, 1024 * 1024), "test workspace"));

        assertTrue(failure.getMessage().contains("symbolic links")
                        || failure.getMessage().contains("non-symlink"),
                "the provider copy must fail closed on a linked source file");
        assertFalse(Files.exists(home.resolve("copy/linked.txt"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void copyWorkspaceRejectsAWindowsJunctionInsteadOfCopyingItsExternalTarget(@TempDir Path home)
            throws Exception {
        Assumptions.assumeTrue(CommandLocator.isWindows(), "NTFS junctions are a Windows-only reparse point");
        Path source = Files.createDirectories(home.resolve("source"));
        Files.writeString(source.resolve("inside.txt"), "inside");
        Path outside = Files.createDirectories(home.resolve("outside"));
        Path secret = Files.writeString(outside.resolve("secret.txt"), "outside-secret");
        Path junction = source.resolve("outside-junction");
        createJunction(junction, outside);
        assertTrue(Files.isDirectory(junction));
        assertFalse(Files.isSymbolicLink(junction));

        IOException failure = assertThrows(IOException.class, () -> ProviderWorkspaceFiles.copyWorkspace(
                source, home.resolve("copy"), new SourceBudgetPolicy(100, 1024 * 1024), "test workspace"));

        assertTrue(failure.getMessage().contains("non-recursable directory"));
        assertFalse(Files.exists(home.resolve("copy/outside-junction/secret.txt"), LinkOption.NOFOLLOW_LINKS),
                "a junction target outside the authorized workspace must never be materialized");
        assertEquals("outside-secret", Files.readString(secret));
    }

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
