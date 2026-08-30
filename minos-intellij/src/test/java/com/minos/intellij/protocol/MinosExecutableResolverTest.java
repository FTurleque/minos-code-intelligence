package com.minos.intellij.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MinosExecutableResolverTest {

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static Path executableFile(Path path, String content) throws Exception {
        Path written = Files.writeString(path, content);
        if (posix()) {
            Files.setPosixFilePermissions(written, PosixFilePermissions.fromString("rwx------"));
        }
        return written;
    }

    @Test
    void resolvesAnAbsoluteLauncherToItsRealRegularFile(@TempDir Path temporary) throws Exception {
        Path launcher = executableFile(temporary.resolve("minos"), "launcher");

        assertEquals(launcher.toRealPath(),
                MinosExecutableResolver.resolve(launcher.toString(), "Linux", Map.of("PATH", "")));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void nonExecutableAbsoluteLauncherIsRejected(@TempDir Path temporary) throws Exception {
        Path launcher = Files.writeString(temporary.resolve("minos"), "launcher");
        Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rw-------"));

        assertThrows(IOException.class,
                () -> MinosExecutableResolver.resolve(launcher.toString(), "Linux", Map.of("PATH", "")));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void nonExecutablePathCandidateIsSkippedInFavorOfALaterExecutableOne(@TempDir Path temporary) throws Exception {
        Path bin1 = Files.createDirectories(temporary.resolve("bin1"));
        Path bin2 = Files.createDirectories(temporary.resolve("bin2"));
        Path nonExecutable = Files.writeString(bin1.resolve("minos"), "hostile");
        Files.setPosixFilePermissions(nonExecutable, PosixFilePermissions.fromString("rw-------"));
        Path executable = executableFile(bin2.resolve("minos"), "trusted");

        Path resolved = MinosExecutableResolver.resolve(
                "minos", "Linux", Map.of("PATH", bin1 + ":" + bin2));

        assertEquals(executable.toRealPath(), resolved,
                "a non-executable file earlier in PATH must not shadow a real executable later in PATH");
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
