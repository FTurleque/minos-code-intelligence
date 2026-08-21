package com.minos.io;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfinedFileOpenerWindowsJunctionTest {

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void refusesAWindowsJunctionInTheAncestorChain(@TempDir Path temp) throws Exception {
        Path root = Files.createDirectories(temp.resolve("project"));
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.writeString(outside.resolve("Secret.java"), "class Secret {}", StandardCharsets.UTF_8);
        Path junction = root.resolve("src");
        Process mklink = new ProcessBuilder(
                "cmd.exe", "/d", "/s", "/c",
                "mklink /J \"" + junction + "\" \"" + outside + "\"")
                .redirectErrorStream(true)
                .start();
        int exit = mklink.waitFor();
        String output = new String(mklink.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Assumptions.assumeTrue(exit == 0, "mklink /J unavailable: " + output);

        assertThrows(ConfinedFileOpener.ConfinementException.class,
                () -> ConfinedFileOpener.openConfinedRegularFile(root, Path.of("src", "Secret.java")));
    }
}
