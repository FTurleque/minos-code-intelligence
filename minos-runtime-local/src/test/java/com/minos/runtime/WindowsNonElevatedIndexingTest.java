package com.minos.runtime;

import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerCapability;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import org.junit.jupiter.api.Test;

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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Proves, end to end, that the Windows AppContainer indexing path never requires administrator
 * privileges: the current test process must itself be unelevated (a CI runner or developer desktop
 * that is somehow elevated invalidates the whole premise, so this fails rather than skips), and a
 * full sandboxed provider execution must still succeed while the JVM running MINOS reports a
 * {@code java.home} the sandbox has no business touching — reproducing an IntelliJ run configuration
 * whose JDK lives under {@code Program Files}.
 */
class WindowsNonElevatedIndexingTest {

    @Test
    void currentProcessIsNotElevated() throws Exception {
        if (WorkerSandboxQualification.currentPlatform() != WorkerSandboxQualification.Platform.WINDOWS) return;
        Path powershell = CommandLocator.windowsPowerShell().orElseThrow();
        Process probe = new ProcessBuilder(
                powershell.toString(),
                "-NoLogo", "-NoProfile", "-NonInteractive", "-Command",
                "[Console]::Out.Write([bool]([Security.Principal.WindowsPrincipal]"
                        + "[Security.Principal.WindowsIdentity]::GetCurrent())"
                        + ".IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator))")
                .redirectErrorStream(true)
                .start();
        String output;
        try (var in = probe.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        assertTrue(probe.waitFor(15, java.util.concurrent.TimeUnit.SECONDS), "elevation probe timed out");
        assertEquals(0, probe.exitValue(), "elevation probe failed: " + output);
        assertFalse(Boolean.parseBoolean(output),
                "this test process is running elevated (Administrator); the non-elevated indexing "
                        + "guarantees this test class exists to prove cannot be validated from here");
    }

    @Test
    void indexingSucceedsUnderAppContainerWhileHostJvmJavaHomeIsUnderASimulatedProgramFiles() throws Exception {
        if (WorkerSandboxQualification.currentPlatform() != WorkerSandboxQualification.Platform.WINDOWS) return;
        Path home = Files.createTempDirectory("minos-non-elevated-home-");
        var discovered = WindowsAppContainerWorkerSandboxBackend.discover(home);
        assumeTrue(discovered.isPresent(), "qualified Windows AppContainer backend is required");
        WindowsAppContainerWorkerSandboxBackend backend = discovered.orElseThrow();
        Path project = Files.createTempDirectory("minos-non-elevated-project-");
        Path childPowerShell = CommandLocator.windowsPowerShell().orElseThrow();
        Path providerScript = project.resolve("provider-child.ps1");
        Files.writeString(providerScript, """
                param([string] $Artifact)
                [System.IO.File]::WriteAllText($Artifact, 'non-elevated-sandbox-artifact')
                exit 0
                """, StandardCharsets.US_ASCII);

        // Simulates MINOS itself running under a JDK selected by an IntelliJ run configuration
        // (typically installed under Program Files): the sandbox must not need to ACL this location,
        // elevated or not, to still complete an indexing run.
        Path simulatedDevJdk = Files.createTempDirectory("minos-non-elevated-simulated-dev-jdk-");
        String originalJavaHome = System.getProperty("java.home");
        System.setProperty("java.home", simulatedDevJdk.toString());
        try {
            IndexingExecutionRequest request = fixtureRequest(project);
            ProcessIndexerExecutor executor = new ProcessIndexerExecutor(
                    "fixture-provider",
                    home,
                    (ignored, runDirectory) -> {
                        Path generated = runDirectory.resolve("provider-generated.scip");
                        return new IndexerProcessPlan(
                                List.of(
                                        childPowerShell.toString(),
                                        "-NoLogo",
                                        "-NoProfile",
                                        "-NonInteractive",
                                        "-ExecutionPolicy",
                                        "Bypass",
                                        "-File",
                                        providerScript.toString(),
                                        generated.toString()),
                                project,
                                Map.of(),
                                generated,
                                Duration.ofSeconds(20));
                    });

            IndexingArtifact artifact = backend.execute(executor, request, WorkerNetworkPolicy.DENY);

            assertEquals("fixture-provider", artifact.indexerId());
            assertTrue(Files.isRegularFile(artifact.finalArtifact()));
            assertEquals("non-elevated-sandbox-artifact",
                    Files.readString(artifact.finalArtifact(), StandardCharsets.UTF_8));
        } finally {
            System.setProperty("java.home", originalJavaHome);
        }
    }

    private static IndexingExecutionRequest fixtureRequest(Path projectRoot) {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                "fixture-provider",
                "1.0.0",
                "Fixture provider",
                Set.of(Language.JAVA),
                Set.of(BuildSystem.MAVEN),
                Set.of(IndexerCapability.SYMBOLS),
                IndexerQualification.QUALIFIED,
                1,
                List.of());
        return new IndexingExecutionRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                projectRoot,
                new IndexerSelection(Language.JAVA, descriptor),
                IndexingMode.FULL,
                List.of());
    }
}
