package com.minos.cli;

import com.minos.application.MinosApplication;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.IndexingRun.ExecutionCheckpoint;
import com.minos.orchestration.IndexingRun.IndexerExecution;
import com.minos.orchestration.ProjectIndexState;
import com.minos.registry.RegisteredProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A4 (V-A4-04) : câblage complet de {@code MinosCliRunner}. La source de reprise d'`index-status` est
 * {@code LocalAutonomousIndexOperations} (ADR 0039 §6) ; sans elle, un run interrompu reprenable
 * disparaîtrait de la sortie. Même fixture que la vue MCP ({@code MinosApplicationMcpBackendResumeStatusTest}).
 */
class MinosCliRunnerResumeStatusTest {

    @Test
    void indexStatusThroughTheRunnerShowsTheInterruptedResumableRun(@TempDir Path temp) throws Exception {
        Path project = temp.resolve("project");
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.writeString(project.resolve("src/App.java"), "class App {}");
        try (MinosApplication application = MinosApplication.open(temp.resolve("home"))) {
            RegisteredProject registered = application.projectRegistry().registerProject(project, "resume-fixture");
            UUID runId = UUID.randomUUID();
            Instant checkpointAt = Instant.now().minusSeconds(120);
            application.indexStateStore().saveRun(new IndexingRun(runId, registered.id(), IndexingRun.Status.INTERRUPTED,
                    IndexingRun.Phase.PROVIDER_EXECUTION, checkpointAt.minusSeconds(60), Optional.of(checkpointAt.plusSeconds(30)),
                    List.of(new IndexerExecution(Language.JAVA, "scip-java",
                            temp.resolve("home/runs").resolve(runId.toString()).resolve("scip-java/index.scip"), Optional.of(
                            new ExecutionCheckpoint(Path.of(""), "0.10.0", 12L, "a".repeat(64), "b".repeat(64),
                                    IndexingMode.FULL, List.of(), checkpointAt)))),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("interrupted"),
                    IndexingRun.CURRENT_FORMAT_VERSION));
            application.indexStateStore().saveProjectState(new ProjectIndexState(registered.id(),
                    ProjectIndexState.Availability.FAILED, Optional.empty(), Optional.of(runId),
                    checkpointAt.plusSeconds(30), Optional.of("interrupted"), Optional.of(runId)));

            StringBuilder output = new StringBuilder();
            StringBuilder error = new StringBuilder();
            int exitCode = MinosCliRunner.run(application,
                    new String[]{"index-status", registered.id().toString(), "--format", "json"}, output, error);

            assertEquals(FindSymbolCommand.SUCCESS, exitCode, error.toString());
            assertTrue(output.toString().contains("\"resumableRunId\":\"" + runId + "\""), output.toString());
            assertTrue(output.toString().contains("\"resumableRunPhase\":\"PROVIDER_EXECUTION\""), output.toString());
            assertTrue(output.toString().contains("\"resumableTargets\":1"), output.toString());
        }
    }
}
