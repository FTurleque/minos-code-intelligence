package com.minos.runtime.local.sandbox;

import com.minos.io.PrivateLocalStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The output of the AppContainer probe is read through a pipe drained into a bounded, private file (AUD-SEC-03): it
 * can no longer grow without limit, the file is not one a child process created, and the files are closed when the
 * probe does not finish, so that the probe folder can be deleted.
 */
@EnabledOnOs(OS.WINDOWS)
class AppContainerProbeBoundedOutputTest {
    private static final Path POWERSHELL =
            Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe");

    private static ProcessBuilder powershell(String command) {
        return new ProcessBuilder(List.of(POWERSHELL.toString(), "-NoLogo", "-NoProfile", "-NonInteractive", "-Command",
                command));
    }

    @Test
    void anOutputBeyondTheBoundIsTruncatedWithTheMarkerAndTheProbeSucceeds(@TempDir Path root) throws Exception {
        Path output = root.resolve("probe-output.log");

        boolean succeeded = WindowsAppContainerWorkerSandboxBackend.probeSucceeds(
                powershell("[Console]::Out.Write('w' * 200000); exit 0"), output, Duration.ofSeconds(30), 4096L);

        assertTrue(succeeded);
        String content = Files.readString(output, StandardCharsets.UTF_8);
        assertTrue(content.contains("[MINOS output truncated at configured byte limit]"), "the marker is there");
        assertTrue(Files.size(output) < 8192, "the file stays near the bound: " + Files.size(output));
    }

    @Test
    void theOutputFileIsPrivate(@TempDir Path root) throws Exception {
        Path output = root.resolve("probe-output.log");

        WindowsAppContainerWorkerSandboxBackend.probeSucceeds(
                powershell("Write-Output 'refused'; exit 3"), output, Duration.ofSeconds(30));

        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(output));
    }

    @Test
    void aProbeWhichDoesNotFinishLeavesItsFolderDeletable(@TempDir Path root) throws Exception {
        Path probeRoot = Files.createDirectory(root.resolve("probe"));
        Path output = probeRoot.resolve("probe-output.log");

        boolean succeeded = WindowsAppContainerWorkerSandboxBackend.probeSucceeds(
                powershell("Start-Sleep -Seconds 60"), output, Duration.ofSeconds(2));

        assertFalse(succeeded);
        com.minos.io.FileTreeOperations.deleteRecursively(probeRoot);
        assertFalse(Files.exists(probeRoot), "no stream or file of the probe is left open");
    }
}
