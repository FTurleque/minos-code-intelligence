package com.minos.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderSandboxSecurityRegressionTest {

    @Test
    void residueReclamationNeverRetainsSensitiveWindowsSandboxPlan(@TempDir Path temp) throws Exception {
        Path runsRoot = Files.createDirectories(temp.resolve("runs"));
        Path run = Files.createDirectories(runsRoot.resolve("run-1").resolve("fixture-provider"));
        Path plan = run.resolve("windows-appcontainer-plan.txt");
        Path metadata = run.resolve("process.txt");
        Files.writeString(plan, "credential-sentinel", StandardCharsets.UTF_8);
        Files.writeString(metadata, "status=SUCCESS", StandardCharsets.UTF_8);

        ProviderResidueReclamation.reclaim(runsRoot, run);

        assertFalse(Files.exists(plan), "provider argv/environment plan must never survive run reclamation");
        assertTrue(Files.isRegularFile(metadata), "MINOS-authored non-secret diagnostics remain retained");
        assertFalse(ProviderResidueReclamation.RETAINED_ENTRIES.contains("windows-appcontainer-plan.txt"));
    }

    @Test
    void assembledWindowsLauncherConsumesPlanImmediatelyAfterParsing() throws Exception {
        String launcher = WindowsContainmentScript.assemble("windows-appcontainer-sandbox-v4.ps1");
        String deletion = "Remove-Item -LiteralPath $Path -Force -ErrorAction Stop";

        assertTrue(launcher.contains(deletion),
                "assembled launcher must consume the credential-bearing plan instead of retaining it");
        assertTrue(launcher.contains("$values = Read-Plan $Plan"),
                "assembled launcher must obtain provider data only through the one-shot plan parser");
    }
}
