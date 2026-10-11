package com.minos.runtime.local.workspace;

import com.minos.source.SourceBudgetPolicy;
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

/** Refus de {@link ProviderWorkspaceFiles} : source invalide, budget dépassé, frontière non nommée. */
class ProviderWorkspaceFilesRefusalTest {

    private static final SourceBudgetPolicy GENEROUS = new SourceBudgetPolicy(100, 1024 * 1024);

    @Test
    void aSourceThatIsNotADirectoryIsRefusedAndNoCopyIsStarted(@TempDir Path home) throws Exception {
        Path source = Files.writeString(home.resolve("not-a-directory.txt"), "content");
        Path target = home.resolve("copy");

        IOException refusal = assertThrows(IOException.class,
                () -> ProviderWorkspaceFiles.copyWorkspace(source, target, GENEROUS, "test workspace"));

        assertEquals("test workspace source workspace is not a directory", refusal.getMessage());
        assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS), "a refused copy must not create its target");
    }

    @Test
    void aFileThatExceedsTheByteBudgetIsRefusedAndItsPartialCopyIsRemoved(@TempDir Path home) throws Exception {
        Path source = Files.createDirectories(home.resolve("source"));
        Files.writeString(source.resolve("large.txt"), "0123456789");
        Path target = home.resolve("copy");
        SourceBudgetPolicy fourBytes = new SourceBudgetPolicy(100, 4);

        IOException refusal = assertThrows(IOException.class,
                () -> ProviderWorkspaceFiles.copyWorkspace(source, target, fourBytes, "test workspace"));

        assertTrue(refusal.getMessage().startsWith("test workspace exceeds source budget"), refusal.getMessage());
        assertFalse(refusal.getMessage().contains(home.toString()),
                "the refusal names the boundary, never an absolute path");
        assertFalse(Files.exists(target.resolve("large.txt"), LinkOption.NOFOLLOW_LINKS),
                "a truncated file must never be left in the provider workspace");
        assertEquals("0123456789", Files.readString(source.resolve("large.txt")), "the source is only read");
    }

    @Test
    void aCopyOrADeletionWithoutANamedBoundaryIsRefused(@TempDir Path home) throws Exception {
        Path source = Files.createDirectories(home.resolve("source"));
        Path workspaces = Files.createDirectories(home.resolve("workspaces"));
        Path workspace = Files.createDirectories(workspaces.resolve("provider-1"));
        Path target = home.resolve("copy");

        IllegalArgumentException copy = assertThrows(IllegalArgumentException.class,
                () -> ProviderWorkspaceFiles.copyWorkspace(source, target, GENEROUS, " "));
        IllegalArgumentException deletion = assertThrows(IllegalArgumentException.class,
                () -> ProviderWorkspaceFiles.deleteTree(workspaces, workspace, ""));

        assertEquals("workspace boundary must not be blank", copy.getMessage());
        assertEquals("workspace boundary must not be blank", deletion.getMessage());
        assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isDirectory(workspace), "a refused deletion must leave the workspace in place");
    }

    @Test
    void theAllowedRootItselfIsNeverDeleted(@TempDir Path home) throws Exception {
        Path workspaces = Files.createDirectories(home.resolve("workspaces"));
        Files.writeString(workspaces.resolve("kept.txt"), "kept");
        Path sameRootSpelledDifferently = workspaces.resolve("provider-1").resolve("..");

        IOException refusal = assertThrows(IOException.class,
                () -> ProviderWorkspaceFiles.deleteTree(workspaces, sameRootSpelledDifferently, "test workspace"));

        assertEquals("test workspace refuses to delete outside its workspace root", refusal.getMessage());
        assertEquals("kept", Files.readString(workspaces.resolve("kept.txt")));
    }
}
