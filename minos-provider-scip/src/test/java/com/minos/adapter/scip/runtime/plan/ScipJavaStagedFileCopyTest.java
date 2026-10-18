package com.minos.adapter.scip.runtime.plan;

import com.minos.source.SourceBudgetPolicy;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The copy of one project file into the staged workspace (AUD-SEC-03) creates a new file and never follows a link. */
class ScipJavaStagedFileCopyTest {

    @Test
    void aProjectFileIsCopiedAsANewFile(@TempDir Path temporary) throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Path source = project.resolve("Visible.java");
        Files.writeString(source, "class Visible {}");
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        Path target = workspace.resolve("Visible.java");

        ScipJavaProcessPlanFactory.copyRegularFileBounded(
                project, source, target, SourceBudgetPolicy.DEFAULT.tracker("test staging"));

        assertEquals("class Visible {}", Files.readString(target));
    }

    @Test
    void aStagedTargetReplacedByALinkIsRefusedAndTheLinkTargetIsLeftIntact(@TempDir Path temporary) throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Path source = project.resolve("Visible.java");
        Files.writeString(source, "class Visible {}");
        Path sentinel = temporary.resolve("sentinel.txt");
        Files.writeString(sentinel, "untouched");
        Path workspace = Files.createDirectories(temporary.resolve("workspace"));
        Path target = workspace.resolve("Visible.java");
        try {
            Files.createSymbolicLink(target, sentinel);
        } catch (UnsupportedOperationException | SecurityException | IOException exception) {
            Assumptions.abort("symbolic links are unavailable: " + exception.getClass().getSimpleName());
        }

        assertThrows(IOException.class, () -> ScipJavaProcessPlanFactory.copyRegularFileBounded(
                project, source, target, SourceBudgetPolicy.DEFAULT.tracker("test staging")));

        assertEquals("untouched", Files.readString(sentinel));
    }
}
