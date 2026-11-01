package com.minos.runtime.local.sandbox;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.runtime.local.process.CommandLocator;
import com.minos.runtime.local.process.IndexerProcessPlan;
import com.minos.runtime.local.process.ProcessIndexerExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Windows boundaries cannot mount a read-only root: a plan that declares one is refused before any plan file,
 * grant or process exists, whether it reaches the AppContainer backend or the Job Object ownership transformer.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsSandboxMountsRefusalTest {

    @TempDir Path temporary;

    @Test
    void theAppContainerBackendRefusesBeforeWritingAPlanOrGrantingAnything() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path run = Files.createDirectory(temporary.resolve("run"));
        WindowsAppContainerWorkerSandboxBackend backend = new WindowsAppContainerWorkerSandboxBackend(home, powershell());

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> backend.sandboxPlan(planWithRoot(), run));

        assertTrue(refused.getMessage().startsWith("the Windows AppContainer sandbox cannot mount"),
                refused.getMessage());
        assertFalse(refused.getMessage().contains(temporary.toString()), refused.getMessage());
        assertFalse(Files.exists(run.resolve("windows-appcontainer-plan.txt")), "no plan file, hence no grant");
    }

    @Test
    void theJobObjectTransformerRefusesThroughTheRealExecutorBeforeAnyProcess() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Path home = Files.createDirectories(temporary.resolve("home"));
        IndexerProcessPlan declared = planWithRoot();
        ProcessIndexerExecutor executor = new ProcessIndexerExecutor("fake-provider", home,
                (request, runDirectory) -> declared);
        ProcessIndexerExecutor.ProcessPlanTransformer transformer = WindowsJobObjectProcessOwnership.discover(home)
                .orElseThrow(() -> new AssertionError("Job Object ownership needs only Windows PowerShell"))
                .transformer();

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> executor.executeSandboxed(request(project), transformer));

        assertTrue(refused.getMessage().startsWith("Windows Job Object process ownership cannot mount"),
                refused.getMessage());
        assertFalse(Files.exists(declared.workingDirectory().resolve("started.txt")), "no provider was started");
    }

    private IndexerProcessPlan planWithRoot() throws Exception {
        Path working = Files.createDirectories(temporary.resolve("working"));
        Path repository = Files.createDirectories(temporary.resolve("repository"));
        return new IndexerProcessPlan(
                List.of(powershell().toString(), "-NoProfile", "-Command",
                        "Set-Content -Path '" + working.resolve("started.txt") + "' -Value x"),
                working, Map.of(), working.resolve("index.scip"), Duration.ofSeconds(30),
                List.of(repository), List.of());
    }

    private static Path powershell() {
        return CommandLocator.windowsPowerShell()
                .orElseThrow(() -> new AssertionError("Windows PowerShell is part of every supported Windows"));
    }

    private static IndexingExecutionRequest request(Path project) {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                "fake-provider", "1", "fake", Set.of(Language.JAVA), Set.of(), Set.of(),
                IndexerQualification.QUALIFIED, 1, List.of());
        return new IndexingExecutionRequest(UUID.randomUUID(), UUID.randomUUID(), project,
                new IndexerSelection(Language.JAVA, descriptor), IndexingMode.FULL, List.of());
    }
}
