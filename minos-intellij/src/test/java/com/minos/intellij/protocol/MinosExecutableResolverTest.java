package com.minos.intellij.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MinosExecutableResolverTest {

    @Test
    void resolvesAnAbsoluteLauncherToItsRealRegularFile(@TempDir Path temporary) throws Exception {
        Path launcher = temporary.resolve("minos");
        Files.writeString(launcher, "launcher");

        assertEquals(launcher.toRealPath(),
                MinosExecutableResolver.resolve(launcher.toString(), "Linux", Map.of("PATH", "")));
    }

    @Test
    void windowsBatchLookupIgnoresCurrentDirectorySemantics(@TempDir Path temporary) throws Exception {
        Path project = temporary.resolve("project");
        Path trustedBin = temporary.resolve("trusted-bin");
        Files.createDirectories(project);
        Files.createDirectories(trustedBin);
        Files.writeString(project.resolve("minos.cmd"), "@echo hostile");
        Path trusted = trustedBin.resolve("minos.cmd");
        Files.writeString(trusted, "@echo trusted");

        // A leading empty PATH element normally means the current working directory. The resolver
        // must ignore it and select only the absolute trusted PATH entry.
        Path resolved = MinosExecutableResolver.resolve(
                "minos.cmd",
                "Windows 11",
                Map.of("Path", ";" + trustedBin, "PATHEXT", ".CMD;.EXE"));

        assertEquals(trusted.toRealPath(), resolved);
    }

    @Test
    void relativeLauncherPathCannotPointIntoTheProject(@TempDir Path temporary) throws Exception {
        Path projectTool = temporary.resolve("tools/minos.cmd");
        Files.createDirectories(projectTool.getParent());
        Files.writeString(projectTool, "@echo hostile");

        assertThrows(IOException.class, () -> MinosExecutableResolver.resolve(
                "tools\\minos.cmd",
                "Windows 11",
                Map.of("PATH", temporary.toString())));
    }

    @Test
    void relativePathEntriesAreNeverUsedForLauncherLookup() {
        assertThrows(IOException.class, () -> MinosExecutableResolver.resolve(
                "minos.cmd",
                "Windows 11",
                Map.of("PATH", ".", "PATHEXT", ".CMD")));
    }
}
