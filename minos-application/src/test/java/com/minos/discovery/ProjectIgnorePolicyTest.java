package com.minos.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectIgnorePolicyTest {

    @Test
    void appliesDirectoryGlobAndNegationRules(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve(".gitignore"), "cache/\n*.tmp\nsecret/**\n");
        Files.writeString(root.resolve(".minosignore"), "generated/**\n!generated/keep/**\n!secret/**\n");

        ProjectIgnorePolicy policy = ProjectIgnorePolicy.load(root);

        assertTrue(policy.isIgnored(Path.of("cache"), true));
        assertFalse(policy.isIgnored(Path.of("cache"), false));
        assertTrue(policy.isIgnored(Path.of("cache/value.txt"), false));
        assertTrue(policy.isIgnored(Path.of("notes.tmp"), false));
        assertTrue(policy.isIgnored(Path.of("generated/drop/value.ts"), false));
        assertFalse(policy.isIgnored(Path.of("generated/keep/value.ts"), false));

        // .minosignore may not re-include something already ignored by .gitignore.
        assertTrue(policy.isIgnored(Path.of("secret/value.txt"), false));
    }

    @Test
    void hardTechnicalExclusionsCannotBeReincluded(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve(".minosignore"), "!node_modules/**\n!target/**\n");

        ProjectIgnorePolicy policy = ProjectIgnorePolicy.load(root);

        assertTrue(policy.isIgnored(Path.of("node_modules/pkg/index.ts"), false));
        assertTrue(policy.isIgnored(Path.of("target/classes/App.class"), false));
    }
}
