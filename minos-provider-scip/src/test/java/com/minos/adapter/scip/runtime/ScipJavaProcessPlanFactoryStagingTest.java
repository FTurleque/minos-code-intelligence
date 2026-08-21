package com.minos.adapter.scip.runtime;

import com.minos.source.ProjectIgnoreRules;
import com.minos.source.SourceBudgetPolicy;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

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

    @Test
    void stagingSkipsASymbolicLinkInsteadOfCopyingItsExternalTarget(@TempDir Path temporary) throws Exception {
        Path project = temporary.resolve("project");
        Path run = temporary.resolve("run");
        Path outside = temporary.resolve("outside-secret.java");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Files.writeString(outside, "class Secret {}", StandardCharsets.UTF_8);
        Path link = project.resolve("Secret.java");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assumptions.abort("symbolic links unavailable: " + unavailable.getMessage());
        }

        Path staged = ScipJavaProcessPlanFactory.prepareWritableWorkspace(project, run);

        assertFalse(Files.exists(staged.resolve("Secret.java")));
        assertTrue(Files.isRegularFile(outside));
    }

    @Test
    void stagingRejectsAWindowsJunctionInsteadOfCopyingItsExternalTarget(@TempDir Path temporary)
            throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
        Path project = temporary.resolve("project");
        Path run = temporary.resolve("run");
        Path outside = temporary.resolve("outside");
        Files.createDirectories(project);
        Files.createDirectories(outside);
        Files.writeString(project.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Files.writeString(outside.resolve("Secret.java"), "class Secret {}", StandardCharsets.UTF_8);
        Path junction = project.resolve("linked");

        Process mklink = new ProcessBuilder(
                "cmd.exe", "/d", "/s", "/c",
                "mklink /J \"" + junction + "\" \"" + outside + "\"")
                .redirectErrorStream(true)
                .start();
        int exit = mklink.waitFor();
        Assumptions.assumeTrue(exit == 0,
                () -> "mklink /J unavailable: " + new String(mklink.getInputStream().readAllBytes(), StandardCharsets.UTF_8));

        assertThrows(IOException.class,
                () -> ScipJavaProcessPlanFactory.prepareWritableWorkspace(project, run));
        assertFalse(Files.exists(run.resolve("workspace")));
        assertTrue(Files.isRegularFile(outside.resolve("Secret.java")));
    }
}
