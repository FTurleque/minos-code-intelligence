package com.minos.source;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectIgnoreRulesConfinementTest {

    @Test
    void ordinaryIgnoreFilesStillApplyRules(@TempDir Path home) throws Exception {
        Path project = Files.createDirectories(home.resolve("project"));
        Files.writeString(project.resolve(".gitignore"), "*.class\n");
        Files.writeString(project.resolve(".minosignore"), "generated/**\n");

        ProjectIgnoreRules rules = ProjectIgnoreRules.load(project);

        assertTrue(rules.isIgnored(Path.of("Main.class"), false));
        assertTrue(rules.isIgnored(Path.of("generated/output.txt"), false));
        assertFalse(rules.isIgnored(Path.of("src/Main.java"), false));
    }

    @Test
    void linkedIgnoreFileCannotImportRulesFromOutsideTheProject(@TempDir Path home) throws Exception {
        Path project = Files.createDirectories(home.resolve("project"));
        Path outside = Files.writeString(home.resolve("outside-ignore"), "*.java\n");
        Path linkedIgnore = project.resolve(".gitignore");
        try {
            Files.createSymbolicLink(linkedIgnore, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "symbolic-link creation is unavailable: " + unavailable.getMessage());
        }

        ProjectIgnoreRules rules = ProjectIgnoreRules.load(project);

        assertFalse(rules.isIgnored(Path.of("src/Main.java"), false),
                "ignore rules must never be loaded through a link outside the authorized project root");
    }
}
