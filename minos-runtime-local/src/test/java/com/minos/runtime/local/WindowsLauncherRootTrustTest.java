package com.minos.runtime.local;

import com.minos.io.PrivateLocalStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who can alter the place a launcher script is read from. A hash of the content proves what the file
 * holds, not who else can replace it: a principal that can modify the directory above, the directory
 * itself or the file could rewrite the script after the check and before PowerShell reads it, and the
 * script then runs outside the sandbox. Everyone (S-1-1-0) stands for such a principal: modifying
 * rights on a directory that another principal's account owns is what an unprivileged attacker has on
 * a shared temporary directory.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsLauncherRootTrustTest {

    private static final String LAUNCHER = "windows-job-object-owner-v1.ps1";

    @TempDir
    Path temporary;

    @Test
    void aRootBelowADirectoryAnotherPrincipalCanModifyIsRefused() throws Exception {
        Path parent = PrivateLocalStorage.ensurePrivateDirectory(temporary.resolve("shared-parent"));
        icacls(parent, "/grant", "*S-1-1-0:(OI)(CI)M");

        IOException refusal = assertThrows(IOException.class,
                () -> SandboxLauncherScript.materialize(parent.resolve("launchers"), LAUNCHER));

        assertFalse(String.valueOf(refusal.getMessage()).contains(temporary.toString()), "no absolute path");
    }

    @Test
    void aLauncherDirectoryThatBecameModifiableByAnotherPrincipalIsRefusedBeforeUse() throws Exception {
        Path parent = PrivateLocalStorage.ensurePrivateDirectory(temporary.resolve("private-parent"));
        SandboxLauncherScript script = SandboxLauncherScript.materialize(parent.resolve("launchers"), LAUNCHER);
        script.verify();

        icacls(script.file().getParent(), "/grant", "*S-1-1-0:(OI)(CI)M");

        assertThrows(IOException.class, script::verify);
    }

    @Test
    void aLauncherFileThatBecameModifiableByAnotherPrincipalIsRefusedBeforeUse() throws Exception {
        Path parent = PrivateLocalStorage.ensurePrivateDirectory(temporary.resolve("private-parent-file"));
        SandboxLauncherScript script = SandboxLauncherScript.materialize(parent.resolve("launchers"), LAUNCHER);

        icacls(script.file(), "/grant", "*S-1-1-0:M");

        assertThrows(IOException.class, script::verify);
    }

    @Test
    void theDefaultRootIsAPrivateDirectoryOfTheUserNotTheSharedTemporaryDirectory() throws Exception {
        Path file = SandboxLauncherScript.materialize(LAUNCHER).file().toAbsolutePath().normalize();

        Path localAppData = Path.of(System.getenv("LOCALAPPDATA")).toAbsolutePath().normalize();
        assertTrue(file.startsWith(localAppData), "the launcher must live under %LOCALAPPDATA%");
        assertFalse(file.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()),
                "the temporary directory is shared with every principal that can write to it");
    }

    private static void icacls(Path path, String... arguments) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(Path.of(System.getenv("SystemRoot"), "System32", "icacls.exe").toString());
        command.add(path.toString());
        command.addAll(List.of(arguments));
        command.add("/q");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), Charset.defaultCharset());
        assertEquals(0, process.waitFor(), "icacls failed: " + output);
    }
}
