package com.minos.runtime.local.process;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import com.minos.orchestration.IndexerQualification;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Read-only roots and masked files survive every transformation of a plan, or the plan is refused before any
 * provider starts: a boundary that drops them would run the provider without the mounts it was planned with.
 */
class ProcessPlanSandboxMountsPropagationTest {

    @TempDir Path temporary;

    @Test
    void copiesKeepTheDeclaredMounts() throws Exception {
        IndexerProcessPlan plan = planWithMounts();

        IndexerProcessPlan rewritten = plan.withCommand(List.of("wrapper", "--", "provider"));
        IndexerProcessPlan reconfigured = plan.withEnvironment(Map.of("PATH", "/usr/bin"));

        assertEquals(plan.readOnlyRoots(), rewritten.readOnlyRoots());
        assertEquals(plan.maskedFiles(), rewritten.maskedFiles());
        assertEquals(List.of("wrapper", "--", "provider"), rewritten.command());
        assertEquals(plan.readOnlyRoots(), reconfigured.readOnlyRoots());
        assertEquals(plan.maskedFiles(), reconfigured.maskedFiles());
        assertTrue(plan.declaresSandboxMounts());
    }

    @Test
    void theLegacyConstructorDeclaresNoMount() throws Exception {
        Path working = Files.createDirectories(temporary.resolve("working"));
        IndexerProcessPlan plan = new IndexerProcessPlan(List.of("provider"), working, Map.of(),
                working.resolve("index.scip"), Duration.ofSeconds(5));

        assertFalse(plan.declaresSandboxMounts());
        plan.requireNoSandboxMounts("any boundary");
    }

    @Test
    void aBoundaryThatCannotMountRefusesWithoutAPath() throws Exception {
        IndexerProcessPlan plan = planWithMounts();

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> plan.requireNoSandboxMounts("a test boundary"));

        assertTrue(refused.getMessage().startsWith("a test boundary cannot mount"), refused.getMessage());
        assertFalse(refused.getMessage().contains(temporary.toString()), refused.getMessage());
    }

    @Test
    void theExecutorRefusesATransformerThatDropsTheMountsBeforeStartingAnything() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Path home = temporary.resolve("home");
        IndexerProcessPlan declared = planWithMounts();
        AtomicBoolean transformed = new AtomicBoolean();
        ProcessIndexerExecutor executor = new ProcessIndexerExecutor("fake-provider", home,
                (request, runDirectory) -> declared);
        ProcessIndexerExecutor.ProcessPlanTransformer dropping = mounting((plan, runDirectory) -> {
            transformed.set(true);
            return new IndexerProcessPlan(List.of("never-started"), plan.workingDirectory(), Map.of(),
                    plan.generatedArtifact(), plan.timeout());
        });

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> executor.executeSandboxed(request(project), dropping));

        assertTrue(transformed.get());
        assertEquals("the process boundary dropped the sandbox mounts the provider plan declares",
                refused.getMessage());
    }

    @Test
    void theExecutorAcceptsATransformerThatKeepsTheMounts() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        IndexerProcessPlan declared = planWithMounts();
        ProcessIndexerExecutor executor = new ProcessIndexerExecutor("fake-provider", temporary.resolve("home"),
                (request, runDirectory) -> declared);
        ProcessIndexerExecutor.ProcessPlanTransformer keeping =
                mounting((plan, runDirectory) -> plan.withCommand(List.of(missingExecutable())));

        // Accepted by the mount check, the plan then fails only where the missing executable is started.
        Exception failure = assertThrows(Exception.class, () -> executor.executeSandboxed(request(project), keeping));

        assertFalse(failure.getMessage() != null && failure.getMessage().contains("dropped the sandbox mounts"),
                String.valueOf(failure.getMessage()));
    }

    @Test
    void theExecutorRefusesABoundaryThatKeepsTheMountsWithoutAnnouncingThatItMountsThem() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        IndexerProcessPlan declared = planWithMounts();
        ProcessIndexerExecutor executor = new ProcessIndexerExecutor("fake-provider", temporary.resolve("home"),
                (request, runDirectory) -> declared);
        ProcessIndexerExecutor.ProcessPlanTransformer silent =
                (plan, runDirectory) -> plan.withCommand(List.of(missingExecutable()));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> executor.executeSandboxed(request(project), silent));

        assertEquals("the process boundary cannot mount the sandbox mounts the provider plan declares",
                refused.getMessage());
    }

    /** A transformer that announces it mounts the declared roots and masks, as only the Linux sandbox does. */
    private static ProcessIndexerExecutor.ProcessPlanTransformer mounting(
            ProcessIndexerExecutor.ProcessPlanTransformer transform) {
        return new ProcessIndexerExecutor.ProcessPlanTransformer() {
            @Override
            public IndexerProcessPlan transform(IndexerProcessPlan plan, Path runDirectory) throws Exception {
                return transform.transform(plan, runDirectory);
            }

            @Override
            public boolean mountsDeclaredSandboxMounts() {
                return true;
            }
        };
    }

    private String missingExecutable() {
        return temporary.resolve("missing-provider-executable").toString();
    }

    private IndexerProcessPlan planWithMounts() throws Exception {
        Path working = Files.createDirectories(temporary.resolve("working"));
        Path repository = Files.createDirectories(temporary.resolve("repository"));
        return new IndexerProcessPlan(List.of("provider"), working, Map.of(), working.resolve("index.scip"),
                Duration.ofSeconds(5), List.of(repository), List.of(temporary.resolve("settings.xml")));
    }

    private static IndexingExecutionRequest request(Path project) {
        IndexerDescriptor descriptor = new IndexerDescriptor(
                "fake-provider", "1", "fake", Set.of(Language.JAVA), Set.of(), Set.of(),
                IndexerQualification.QUALIFIED, 1, List.of());
        return new IndexingExecutionRequest(UUID.randomUUID(), UUID.randomUUID(), project,
                new IndexerSelection(Language.JAVA, descriptor), IndexingMode.FULL, List.of());
    }
}
