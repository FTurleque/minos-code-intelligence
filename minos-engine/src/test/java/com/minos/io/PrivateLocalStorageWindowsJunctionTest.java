package com.minos.io;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PrivateLocalStorageWindowsJunctionTest {

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void junctionIsExposedAndRejectedBeforePrivateStorageHardening(@TempDir Path temp) throws Exception {
        Path outside = Files.createDirectories(temp.resolve("outside-private-store"));
        Path junction = temp.resolve("junction-private-store");
        Process mklink = new ProcessBuilder(
                "cmd.exe", "/d", "/s", "/c",
                "mklink /J \"" + junction + "\" \"" + outside + "\"")
                .redirectErrorStream(true)
                .start();
        int exit = mklink.waitFor();
        String output = new String(mklink.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Assumptions.assumeTrue(exit == 0, "mklink /J unavailable: " + output);

        assertEquals(PrivateLocalStorage.Privacy.EXPOSED, PrivateLocalStorage.privacyOf(junction));
        assertThrows(IOException.class, () -> PrivateLocalStorage.verifyPrivateDirectory(junction));
        assertThrows(IOException.class, () -> PrivateLocalStorage.ensurePrivateDirectory(junction));
    }
}
