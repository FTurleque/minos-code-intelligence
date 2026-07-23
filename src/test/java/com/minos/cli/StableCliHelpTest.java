package com.minos.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class StableCliHelpTest {

    @Test
    void commandHelpIsSuccessfulAndDoesNotCreateMinosHome(@TempDir Path root) throws Exception {
        Path home = root.resolve("absent-home");
        List<String[]> commands = List.of(
                new String[]{"project", "add", "--help"},
                new String[]{"project", "list", "--help"},
                new String[]{"project", "inspect", "--help"},
                new String[]{"inspect", "--help"},
                new String[]{"index-status", "--help"},
                new String[]{"index", "--help"},
                new String[]{"architecture", "--help"},
                new String[]{"impact", "--help"}
        );

        for (String[] command : commands) {
            StringBuilder output = new StringBuilder();
            StringBuilder error = new StringBuilder();
            assertEquals(0, MinosLauncher.run(home, command, output, error), String.join(" ", command));
            assertFalse(output.isEmpty(), String.join(" ", command));
            assertEquals("", error.toString(), String.join(" ", command));
            assertFalse(Files.exists(home), String.join(" ", command));
        }
    }
}
