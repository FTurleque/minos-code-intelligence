package com.minos.runtime.local;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The leak check must see an explicit AppContainer grant, and ignore the entries a child inherits from it. */
@EnabledOnOs(OS.WINDOWS)
class AppContainerGrantLeakCheckTest {
    private static final String SID = "S-1-15-2-1111111111-2222222222-3333333333-1444444444-1555555555-1666666666-1777777777";

    @Test
    void anExplicitGrantIsSeenAndItsInheritedCopiesAreNot(@TempDir Path root) throws Exception {
        Path granted = Files.createDirectory(root.resolve("granted dir"));
        Files.writeString(granted.resolve("child.txt"), "x");
        icacls(granted.toString(), "/grant", "*" + SID + ":(OI)(CI)RX", "/q");
        try {
            Set<String> entries = AppContainerGrantLeakCheck.explicitEntries(root);

            assertEquals(1, entries.size(), entries.toString());
            assertEquals(granted + "|" + SID, entries.iterator().next(), "the path keeps its spaces");
        } finally {
            icacls(granted.toString(), "/remove:g", "*" + SID, "/q");
        }
        assertTrue(AppContainerGrantLeakCheck.explicitEntries(root).isEmpty());
    }

    private static void icacls(String... arguments) throws Exception {
        String[] command = new String[arguments.length + 1];
        command[0] = "icacls.exe";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), String.join(" ", command));
    }
}
