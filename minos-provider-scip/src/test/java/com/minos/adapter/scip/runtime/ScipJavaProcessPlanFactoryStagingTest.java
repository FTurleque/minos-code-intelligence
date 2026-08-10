package com.minos.adapter.scip.runtime;

import com.minos.source.SourceBudgetPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScipJavaProcessPlanFactoryStagingTest {

    @Test
    void stagingUsesSharedMinosIgnoreRulesIncludingNegation(@TempDir Path temporary) throws Exception {
        Path project = temporary.resolve("project");
        Path run = temporary.resolve("run");
        Files.createDirectories(project.resolve("ignored"));
        Files.writeString(project.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Files.writeString(project.resolve(".minosignore"), ".env\nignored/**\n!ignored/keep.txt\n",
                StandardCharsets.UTF_8);
        Files.writeString(project.resolve(".env"), "TOP_SECRET=1", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("ignored/drop.txt"), "drop", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("ignored/keep.txt"), "keep", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("Visible.java"), "class Visible {}", StandardCharsets.UTF_8);

        Path staged = ScipJavaProcessPlanFactory.prepareWritableWorkspace(project, run);

        assertTrue(Files.isRegularFile(staged.resolve("pom.xml")));
        assertTrue(Files.isRegularFile(staged.resolve("Visible.java")));
        assertTrue(Files.isRegularFile(staged.resolve("ignored/keep.txt")));
        assertFalse(Files.exists(staged.resolve(".env")));
        assertFalse(Files.exists(staged.resolve("ignored/drop.txt")));
    }

    @Test
    void stagingFailsClosedAndDeletesPartialWorkspaceWhenByteBudgetIsExceeded(@TempDir Path temporary)
            throws Exception {
        Path project = temporary.resolve("project");
        Path run = temporary.resolve("run");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Files.write(project.resolve("Huge.java"), new byte[128]);

        SourceBudgetPolicy tinyBudget = new SourceBudgetPolicy(100, 32);
        assertThrows(IOException.class,
                () -> ScipJavaProcessPlanFactory.prepareWritableWorkspace(project, run, tinyBudget));
        assertFalse(Files.exists(run.resolve("workspace")));
    }
}
