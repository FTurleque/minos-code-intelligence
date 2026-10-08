package com.minos.runtime.local;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-H22 : {@code scripts/windows/Find-MinosAppContainerGrants.ps1} compte les entrées d'AppContainer
 * orphelines et nomme leur chemin sans rien modifier ; il ne les retire qu'avec {@code -Remove}.
 */
@EnabledOnOs(OS.WINDOWS)
class OrphanAppContainerGrantDiagnosticTest {
    private static final String SID = "S-1-15-2-1111111111-2222222222-3333333333-1444444444-1555555555-1666666666-1777777777";
    private static final Path SCRIPT = script();

    private static Path script() {
        Path relative = Path.of("scripts", "windows", "Find-MinosAppContainerGrants.ps1");
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            if (Files.isRegularFile(directory.resolve(relative))) return directory.resolve(relative);
        }
        throw new IllegalStateException("repository script not found above " + Path.of("").toAbsolutePath());
    }

    @Test
    void orphanGrantsAreReportedWithoutChangeAndRemovedOnlyOnRequest(@TempDir Path root) throws Exception {
        Path granted = Files.createDirectory(root.resolve("runtime root"));
        run("icacls.exe", granted.toString(), "/grant", "*" + SID + ":(OI)(CI)RX", "/q");
        try {
            String report = diagnose(root, "");
            assertTrue(report.contains("1 orphan AppContainer entry on 1 path(s)."), report);
            assertTrue(report.contains(granted.toString()), report);
            assertTrue(report.contains("Nothing was changed"), report);
            assertEquals(1, AppContainerGrantLeakCheck.explicitEntries(root).size(), "a report changes nothing");

            diagnose(root, "-Remove -WhatIf");
            assertEquals(1, AppContainerGrantLeakCheck.explicitEntries(root).size(), "-WhatIf changes nothing");

            diagnose(root, "-Remove -Confirm:$false");
            assertTrue(AppContainerGrantLeakCheck.explicitEntries(root).isEmpty(), "-Remove removes the orphan grant");
        } finally {
            run("icacls.exe", granted.toString(), "/remove:g", "*" + SID, "/q");
        }
    }

    private static String diagnose(Path root, String options) throws Exception {
        String command = "& '" + SCRIPT + "' -MinosHome '" + root.resolve("no-home") + "' -Path '" + root + "' " + options;
        return run("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", command);
    }

    private static String run(String... command) throws Exception {
        List<String> arguments = new ArrayList<>(List.of(command));
        ProcessBuilder builder = new ProcessBuilder(arguments).redirectErrorStream(true);
        builder.environment().remove("PSModulePath");
        builder.environment().remove("JAVA_HOME");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), Charset.defaultCharset());
        assertTrue(process.waitFor(2, TimeUnit.MINUTES), output);
        assertEquals(0, process.exitValue(), output);
        return output;
    }
}
