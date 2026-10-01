package com.minos.runtime.local;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The environment a trusted PowerShell launcher is started with. The surefire configuration of this
 * module puts a dummy "secret" in the environment of the test JVM; a MINOS process may carry real
 * tokens and passwords, and a launcher has no use for any of them.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsLauncherEnvironmentTest {

    private static final String SENTINEL = "MINOS_TEST_SECRET_SENTINEL";

    @TempDir
    Path temporary;

    @Test
    void aSecretOfTheParentEnvironmentNeverReachesATrustedLauncher() throws Exception {
        assertNotNull(System.getenv(SENTINEL),
                "run this test through Maven: the module's surefire configuration defines " + SENTINEL);

        String seen = launcherView("[string]$env:" + SENTINEL);

        assertEquals("", seen, "the launcher must not see variables outside the explicit allow-list");
    }

    @Test
    void theLauncherKeepsTheNonSecretMetadataPowerShellNeedsToStart() throws Exception {
        String seen = launcherView("[string]$env:SystemRoot + '|' + [string]$env:TEMP + '|' + [string]$env:PATH");

        String[] parts = seen.split(java.util.regex.Pattern.quote("|"), -1);
        assertEquals(3, parts.length, seen);
        assertFalse(parts[0].isBlank(), "SystemRoot is required by PowerShell");
        assertFalse(parts[1].isBlank(), "TEMP is required by the launcher");
        assertTrue(parts[2].toLowerCase(java.util.Locale.ROOT).contains("system32"), "PATH must stay usable");
    }

    /** Runs a real PowerShell as a trusted launcher through the executor and returns what it wrote. */
    private String launcherView(String expression) throws Exception {
        Path powershell = CommandLocator.windowsPowerShell()
                .orElseThrow(() -> new AssertionError("PowerShell is required on Windows"));
        Path project = Files.createDirectories(temporary.resolve("project-" + UUID.randomUUID()));
        ProcessIndexerExecutor executor = new ProcessIndexerExecutor(
                "fake-provider",
                temporary.resolve("home-" + UUID.randomUUID()),
                (request, runDirectory) -> new IndexerProcessPlan(
                        List.of("unused"), project, Map.of(), runDirectory.resolve("index.scip"),
                        Duration.ofMinutes(1)));

        var artifact = executor.executeSandboxed(request(project), new ProcessIndexerExecutor.ProcessPlanTransformer() {
            @Override
            public IndexerProcessPlan transform(IndexerProcessPlan plan, Path runDirectory) {
                String script = "[IO.File]::WriteAllText('" + plan.generatedArtifact() + "', ("
                        + expression + ") + '.')";
                return new IndexerProcessPlan(
                        List.of(powershell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
                                "-Command", script),
                        plan.workingDirectory(),
                        Map.of(),
                        plan.generatedArtifact(),
                        Duration.ofMinutes(1));
            }

            @Override
            public boolean isTrustedLauncher() {
                return true;
            }
        });
        String written = Files.readString(artifact.finalArtifact(), StandardCharsets.UTF_8);
        assertTrue(written.endsWith("."), written);
        return written.substring(0, written.length() - 1);
    }

    private static IndexingExecutionRequest request(Path project) {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                "fake-provider", "1", "fake", Set.of(Language.JAVA), Set.of(), Set.of(),
                IndexerQualification.QUALIFIED, 1, List.of());
        return new IndexingExecutionRequest(
                UUID.randomUUID(), UUID.randomUUID(), project,
                new IndexerSelection(Language.JAVA, descriptor), IndexingMode.FULL, List.of());
    }
}
