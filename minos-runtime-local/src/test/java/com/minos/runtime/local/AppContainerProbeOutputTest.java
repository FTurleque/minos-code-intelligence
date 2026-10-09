package com.minos.runtime.local;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-H18: a probe that writes far more than a pipe holds still completes. With its output on a pipe read only
 * after exit, such a process blocked on its own write and the probe timed out.
 */
@EnabledOnOs(OS.WINDOWS)
class AppContainerProbeOutputTest {
    private static final Path POWERSHELL =
            Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe");

    @Test
    void aProbeWritingMoreThanAPipeHoldsCompletesWithinTheTimeout(@TempDir Path root) throws Exception {
        Path output = root.resolve("probe-output.log");

        boolean succeeded = WindowsAppContainerWorkerSandboxBackend.runProbe(
                powershell("[Console]::Out.Write('w' * 1048576); exit 0"), output, Duration.ofSeconds(30));

        assertTrue(succeeded, "a probe that exits 0 after a large output must succeed, not time out");
        assertTrue(Files.size(output) >= 1_048_576, "the whole output reaches the file");
    }

    @Test
    void aFailingProbeIsStillAFailure(@TempDir Path root) throws Exception {
        boolean succeeded = WindowsAppContainerWorkerSandboxBackend.runProbe(
                powershell("Write-Output 'refused'; exit 3"), root.resolve("probe-output.log"), Duration.ofSeconds(30));

        assertFalse(succeeded);
    }

    private static ProcessBuilder powershell(String command) {
        return new ProcessBuilder(List.of(POWERSHELL.toString(), "-NoLogo", "-NoProfile", "-NonInteractive", "-Command",
                command));
    }
}
