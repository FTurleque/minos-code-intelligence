package com.minos.packaging;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Runs a test harness under Windows PowerShell 5.1, the installer's runtime, for the installer script contracts. */
final class WindowsPowerShellHarness {

    private WindowsPowerShellHarness() {
    }

    /**
     * Runs {@code harness} by the absolute path of {@code powershell.exe}, with an allow-listed environment, in
     * {@code work}; fails unless it exits 0 within a minute, and returns its merged output.
     */
    static String run(Path work, Path harness, String... arguments) throws Exception {
        String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        Path powershell = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        List<String> command = new ArrayList<>(List.of(powershell.toString(), "-NoProfile",
                "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", harness.toString()));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command);
        Map<String, String> environment = builder.environment();
        Map<String, String> inherited = Map.copyOf(environment);
        environment.clear();
        for (String name : List.of("SystemRoot", "windir", "TEMP", "TMP", "USERPROFILE", "LOCALAPPDATA",
                "APPDATA", "ComSpec")) {
            String value = inherited.get(name);
            if (value != null) environment.put(name, value);
        }
        Path outputFile = work.resolve("harness-output.txt");
        builder.directory(work.toFile()).redirectErrorStream(true).redirectOutput(outputFile.toFile());
        Process process = builder.start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("PowerShell harness timed out");
        }
        String output = Files.readString(outputFile, StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), "PowerShell harness failed:\n" + output);
        return output;
    }
}
