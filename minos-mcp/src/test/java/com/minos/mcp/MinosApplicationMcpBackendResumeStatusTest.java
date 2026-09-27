package com.minos.mcp;

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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R1 lot 2 : minos_index_status expose le run reprenable (ADR 0039 §6). */
class MinosApplicationMcpBackendResumeStatusTest {

    @Test
    void indexStatusExposesTheResumableRunWithoutAnyPath(@TempDir Path temp) throws Exception {
        Path project = temp.resolve("project");
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.writeString(project.resolve("src/App.java"), "class App {}");
        MinosApplication application = MinosApplication.open(temp.resolve("home"));
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

        String status = new MinosApplicationMcpBackend(application).indexStatus("resume-fixture");

        assertTrue(status.contains("\"resumableRunId\":\"" + runId + "\""), status);
        assertTrue(status.contains("\"resumableRunPhase\":\"PROVIDER_EXECUTION\""), status);
        assertTrue(status.contains("\"resumableTargets\":1"), status);
        assertTrue(status.contains("\"resumableCheckpointAgeSeconds\":"), status);
        assertFalse(status.contains(temp.toString().replace('\\', '/')), "no absolute path in the status");
        assertFalse(status.contains("index.scip"), "artifact locations are not exposed");

        String noResume = new MinosApplicationMcpBackend(application).indexStatus("resume-fixture").replace(
                "\"resumableRunId\":\"" + runId + "\"", "");
        assertTrue(noResume.contains("\"state\":\"FAILED\""), noResume);
    }
}
