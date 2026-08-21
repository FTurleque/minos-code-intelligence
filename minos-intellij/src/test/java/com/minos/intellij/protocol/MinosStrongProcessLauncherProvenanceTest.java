package com.minos.intellij.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinosStrongProcessLauncherProvenanceTest {

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void commandProcessorIsCanonicalSystem32AndNeverComSpec() throws Exception {
        Path actual = MinosStrongProcessLauncher.resolveWindowsExecutable("cmd.exe");
        Path expected = Path.of(System.getenv("SystemRoot"), "System32", "cmd.exe").toRealPath();

        assertTrue(actual.isAbsolute());
        assertTrue(Files.isRegularFile(actual));
        assertEquals(expected, actual.toRealPath(),
                "IntelliJ batch execution must be anchored to System32 cmd.exe rather than ComSpec/PATH");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void systemdSecurityAuthorityUsesRootOwnedSystemExecutables() throws Exception {
        Path systemctl = MinosStrongProcessLauncher.linuxSystemExecutable("systemctl");
        Path systemdRun = MinosStrongProcessLauncher.linuxSystemExecutable("systemd-run");

        assertRootOwnedExecutable(systemctl);
        assertRootOwnedExecutable(systemdRun);
    }

    private static void assertRootOwnedExecutable(Path executable) throws Exception {
        assertTrue(executable.isAbsolute());
        assertTrue(Files.isRegularFile(executable));
        assertTrue(Files.isExecutable(executable));
        assertEquals(0L, ((Number) Files.getAttribute(executable, "unix:uid")).longValue(),
                "security-authority executable must be root-owned");
    }
}
