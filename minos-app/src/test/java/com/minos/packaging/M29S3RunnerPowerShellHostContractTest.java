package com.minos.packaging;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M29S3RunnerPowerShellHostContractTest {

    @Test
    void s3HandshakeReusesCurrentPowerShellHostInsteadOfAssumingWindowsPowerShellInPath() throws Exception {
        Path root = repoRoot();
        String runner = normalizedText(root.resolve("scripts/m29/run-s3.ps1"));
        String s4 = normalizedText(root.resolve("scripts/m29/run-s4.ps1"));
        String s5 = normalizedText(root.resolve("scripts/m29/run-s5.ps1"));

        assertTrue(runner.contains("function Resolve-PowerShellHost"));
        assertTrue(runner.contains("Get-Process -Id $PID"));
        assertTrue(runner.contains("Join-Path $PSHOME 'pwsh.exe'"));
        assertTrue(runner.contains("Join-Path $PSHOME 'powershell.exe'"));
        assertTrue(runner.contains("Resolve-PowerShellHost"));
        assertTrue(runner.contains("if ($Current -and -not [string]::IsNullOrWhiteSpace($Current.Path) -and (Test-Path -LiteralPath $Current.Path -PathType Leaf))"),
                "the current-host probe must remain parseable by Windows PowerShell 5.1");
        assertFalse(runner.contains("$Current.Path)\n            -and"),
                "Windows PowerShell 5.1 rejects the historical newline before -and in this if condition");
        assertFalse(runner.contains("(Get-Command powershell.exe -ErrorAction Stop).Source"),
                "S3 must work from PowerShell 7 hosts where legacy powershell.exe is not on PATH");

        assertTrue(s4.contains("[System.Management.Automation.Language.Parser]::ParseFile"),
                "S4 must parse downstream PowerShell gates before Maven or Docker work");
        assertTrue(s4.contains("'scripts\\m29\\run-s3.ps1', 'scripts\\m29\\run-s5.ps1'"));
        assertTrue(s4.contains("M29-S4 PowerShell parse preflight:"));
        assertTrue(s4.contains("PowerShell parse failed"));

        assertTrue(s5.contains("M29-S5 exact-head mismatch"));
        assertTrue(s5.contains("SemanticProvider = 'local-hash'"));
        assertTrue(s5.contains("M29-S5 AUTONOMOUS INDEXING AND VECTOR LIFECYCLE QUALIFICATION SUCCESS"));
    }

    private static String normalizedText(Path path) throws IOException {
        return Files.readString(path).replace("\r\n", "\n").replace('\r', '\n');
    }

    private static Path repoRoot() throws IOException {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        for (int i = 0; i < 5 && candidate != null; i++, candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))
                    && Files.isRegularFile(candidate.resolve("docker/compose.mcp.prod.yaml"))) {
                return candidate;
            }
        }
        throw new IOException("repository root not found from " + Path.of("").toAbsolutePath());
    }
}
