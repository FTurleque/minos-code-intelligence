package com.minos.runtime.local.wsl;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Windows side of the jar probe: one direct {@code wsl.exe} command, its lines read back, failures unknown. */
class WslCommandJarProbeTest {

    private static final Path WSL = Path.of("wsl.exe").toAbsolutePath();
    private static final Path JAR = Path.of("minos.jar").toAbsolutePath();
    private static final String MAIN = "com.minos.cli.MinosLauncher";

    @Test
    void theJarRunsItsProbeModeInTheDistributionWithoutShellOrScope() {
        List<List<String>> commands = new ArrayList<>();
        List<Duration> timeouts = new ArrayList<>();
        WslCommandRunner runner = (command, timeout) -> {
            commands.add(command);
            timeouts.add(timeout);
            return new WslCommandResult(0, (WslJarProbeProtocol.PROTOCOL + " maven OK\n"
                    + WslJarProbeProtocol.PROTOCOL + " project-jdk OK\n"
                    + WslJarProbeProtocol.PROTOCOL + " maven-repository OK\n").getBytes(StandardCharsets.UTF_8));
        };

        List<WslCheck> checks = new WslCommandJarProbe(WSL, runner, JAR, MAIN, path -> "/mnt/c/minos/lib/minos.jar")
                .probe("Ubuntu");

        assertEquals(List.of(List.of(WSL.toString(), "-d", "Ubuntu", "--exec", "java", "-cp",
                "/mnt/c/minos/lib/minos.jar", MAIN, "internal", "wsl-worker", "--probe")), commands);
        assertEquals(List.of(WslCommandJarProbe.TIMEOUT), timeouts);
        assertEquals(3, checks.size());
        assertTrue(checks.stream().allMatch(check -> check.state() == WslCheckState.OK), checks.toString());
    }

    @Test
    void aFailedOrUnstartableProbeReportsNothingSoItsChecksStayUnknown() {
        WslCommandRunner failing = (command, timeout) -> new WslCommandResult(1,
                (WslJarProbeProtocol.PROTOCOL + " maven OK\n").getBytes(StandardCharsets.UTF_8));
        WslCommandRunner unstartable = (command, timeout) -> {
            throw new IOException("cannot start");
        };

        assertEquals(List.of(), new WslCommandJarProbe(WSL, failing, JAR, MAIN, path -> "/mnt/c/minos.jar")
                .probe("Ubuntu"));
        assertEquals(List.of(), new WslCommandJarProbe(WSL, unstartable, JAR, MAIN, path -> "/mnt/c/minos.jar")
                .probe("Ubuntu"));
    }

    @Test
    void anInvalidDistributionNameRunsNoCommand() {
        WslCommandRunner forbidden = (command, timeout) -> {
            throw new AssertionError("no command for an invalid name");
        };
        WslCommandJarProbe probe = new WslCommandJarProbe(WSL, forbidden, JAR, MAIN, path -> "/mnt/c/minos.jar");

        assertThrows(IllegalArgumentException.class, () -> probe.probe("--exec"));
        assertThrows(IllegalArgumentException.class, () -> probe.probe(null));
    }

    @Test
    void theProductionMappingPutsTheJarUnderTheWslMountOfItsDrive() {
        List<List<String>> commands = new ArrayList<>();
        WslCommandRunner runner = (command, timeout) -> {
            commands.add(command);
            return new WslCommandResult(0, new byte[0]);
        };

        new WslCommandJarProbe(WSL, runner, Path.of("C:\\Program Files\\MINOS\\lib\\minos.jar"), MAIN)
                .probe("Ubuntu");

        assertEquals("/mnt/c/Program Files/MINOS/lib/minos.jar", commands.getFirst().get(6));
        assertThrows(IllegalArgumentException.class, () -> new WslCommandJarProbe(Path.of("wsl.exe"), runner, JAR,
                MAIN), "wsl.exe must be absolute");
    }
}
