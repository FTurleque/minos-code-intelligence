package com.minos.runtime.local;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** TEMPORARY DIAGNOSTIC (to be removed): which inherited variables make a PowerShell launcher start fast. */
@EnabledOnOs(OS.WINDOWS)
class WindowsLauncherStallDiagTest {

    @TempDir
    Path temporary;

    @Test
    void findTheVariablesThatMakeThePowerShellLauncherStart() throws Exception {
        Path script = temporary.resolve("probe.ps1");
        Files.writeString(script, "Add-Type -TypeDefinition 'public class MinosProbeT { public static int A() { return 1; } }'\n"
                + "[Console]::Out.WriteLine('probe-done')\n", StandardCharsets.US_ASCII);
        Map<String, String> full = new LinkedHashMap<>(System.getenv());
        List<String> keys = new ArrayList<>(full.keySet());

        Map<String, String> whitelisted = new LinkedHashMap<>();
        ProcessBuilder shaped = new ProcessBuilder();
        shaped.environment().clear();
        shaped.environment().putAll(full);
        ProviderProcessEnvironment.applyForTrustedLauncher(shaped, Map.of());
        shaped.environment().forEach(whitelisted::put);

        List<String> extra = new ArrayList<>();
        for (String key : keys) {
            boolean kept = whitelisted.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(key));
            if (!kept) extra.add(key);
        }
        System.err.println("DIAGSTALL whitelisted=" + whitelisted.size() + " extra=" + extra.size());
        long full1 = run(script, full);
        long slim = run(script, whitelisted);
        System.err.println("DIAGSTALL full=" + full1 + "ms whitelisted=" + slim + "ms");
        if (slim < 6000 || full1 >= 6000) {
            System.err.println("DIAGSTALL not reproduced in isolation (full=" + full1 + " slim=" + slim + ")");
            return;
        }
        // Delta search: smallest group of extra variables whose addition makes the start fast.
        List<String> suspects = new ArrayList<>(extra);
        while (suspects.size() > 1) {
            int half = suspects.size() / 2;
            List<String> first = new ArrayList<>(suspects.subList(0, half));
            List<String> second = new ArrayList<>(suspects.subList(half, suspects.size()));
            long withFirst = run(script, add(whitelisted, full, first));
            System.err.println("DIAGSTALL first-half(" + first.size() + ")=" + withFirst + "ms");
            if (withFirst < 6000) {
                suspects = first;
                continue;
            }
            long withSecond = run(script, add(whitelisted, full, second));
            System.err.println("DIAGSTALL second-half(" + second.size() + ")=" + withSecond + "ms");
            if (withSecond < 6000) {
                suspects = second;
                continue;
            }
            System.err.println("DIAGSTALL needs variables from both halves: " + first + " | " + second);
            return;
        }
        System.err.println("DIAGSTALL the variable that fixes the start: " + suspects);
    }

    private static Map<String, String> add(Map<String, String> base, Map<String, String> full, List<String> names) {
        Map<String, String> merged = new LinkedHashMap<>(base);
        for (String name : names) merged.put(name, full.get(name));
        return merged;
    }

    private static long run(Path script, Map<String, String> environment) throws Exception {
        Path powershell = Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        ProcessBuilder builder = new ProcessBuilder(powershell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-File", script.toString());
        builder.environment().clear();
        builder.environment().putAll(environment);
        builder.redirectErrorStream(true);
        long started = System.nanoTime();
        Process process = builder.start();
        if (!process.waitFor(40, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return 40_000;
        }
        process.getInputStream().readAllBytes();
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
