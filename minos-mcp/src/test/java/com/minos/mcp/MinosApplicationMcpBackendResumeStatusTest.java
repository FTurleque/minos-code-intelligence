package com.minos.mcp;

import com.minos.application.MinosApplication;
import com.minos.bootstrap.ResumableRunFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R1 lot 2 : minos_index_status expose le run reprenable (ADR 0039 §6). */
class MinosApplicationMcpBackendResumeStatusTest {

    @Test
    void indexStatusExposesTheResumableRunWithoutAnyPath(@TempDir Path temp) throws Exception {
        MinosApplication application = MinosApplication.open(temp.resolve("home"));
        UUID runId = ResumableRunFixtures.seedInterruptedResumableRun(application, temp);

        String status = new MinosApplicationMcpBackend(application).indexStatus(ResumableRunFixtures.PROJECT_NAME);

        assertTrue(status.contains("\"resumableRunId\":\"" + runId + "\""), status);
        assertTrue(status.contains("\"resumableRunPhase\":\"PROVIDER_EXECUTION\""), status);
        assertTrue(status.contains("\"resumableTargets\":1"), status);
        assertTrue(status.contains("\"resumableCheckpointAgeSeconds\":"), status);
        assertFalse(status.contains(temp.toString().replace('\\', '/')), "no absolute path in the status");
        assertFalse(status.contains("index.scip"), "artifact locations are not exposed");

        String noResume = new MinosApplicationMcpBackend(application).indexStatus(ResumableRunFixtures.PROJECT_NAME).replace(
                "\"resumableRunId\":\"" + runId + "\"", "");
        assertTrue(noResume.contains("\"state\":\"FAILED\""), noResume);
    }
}
