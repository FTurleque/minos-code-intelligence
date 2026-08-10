package com.minos.adapter.scip.runtime;

import com.minos.source.ProjectIgnoreRules;
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
        Files.createDirectories(project.resolve("ignored/keep"));
        Files.writeString(project.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Files.writeString(project.resolve(".minosignore"), ".env\nignored/**\n!ignored/keep/**\n",
                StandardCharsets.UTF_8);
        Files.writeString(project.resolve(".env"), "TOP_SECRET=1", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("ignored/drop.txt"), "drop", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("ignored/keep/value.txt"), "keep", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("Visible.java"), "class Visible {}", StandardCharsets.UTF_8);

        ProjectIgnoreRules rules = ProjectIgnoreRules.load(project);
        assertTrue(rules.isIgnored(Path.of(".env"), false), "shared rules must hide .env");
        assertTrue(rules.isIgnored(Path.of("ignored/drop.txt"), false), "shared rules must hide ignored subtree");
        assertFalse(rules.isIgnored(Path.of("ignored/keep/value.txt"), false),
                "qualified negation semantics must re-include the keep subtree");

        Path staged = ScipJavaProcessPlanFactory.prepareWritableWorkspace(project, run);

        assertTrue(Files.isRegularFile(staged.resolve("pom.xml")), "pom.xml must remain provider-visible");
        assertTrue(Files.isRegularFile(staged.resolve("Visible.java")), "visible source must be staged");
        assertTrue(Files.isRegularFile(staged.resolve("ignored/keep/value.txt")),
                "explicitly re-included source must be staged");
        assertFalse(Files.exists(staged.resolve(".env")), "ignored .env must never reach the provider workspace");
        assertFalse(Files.exists(staged.resolve("ignored/drop.txt")),
                "ignored source must never reach the provider workspace");
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
