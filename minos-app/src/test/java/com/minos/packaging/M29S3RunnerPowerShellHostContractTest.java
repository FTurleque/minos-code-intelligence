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
        String runner = normalizedText(repoRoot().resolve("scripts/m29/run-s3.ps1"));

        assertTrue(runner.contains("function Resolve-PowerShellHost"));
        assertTrue(runner.contains("Get-Process -Id $PID"));
        assertTrue(runner.contains("Join-Path $PSHOME 'pwsh.exe'"));
        assertTrue(runner.contains("Join-Path $PSHOME 'powershell.exe'"));
        assertTrue(runner.contains("Resolve-PowerShellHost"));
        assertFalse(runner.contains("(Get-Command powershell.exe -ErrorAction Stop).Source"),
                "S3 must work from PowerShell 7 hosts where legacy powershell.exe is not on PATH");
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
