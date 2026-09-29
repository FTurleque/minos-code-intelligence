package com.minos.cli;

import com.minos.application.MinosApplication;
import com.minos.bootstrap.ResumableRunFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A4 (V-A4-04) : câblage complet de {@code MinosCliRunner}. La source de reprise d'`index-status` est
 * {@code LocalAutonomousIndexOperations} (ADR 0039 §6) ; sans elle, un run interrompu reprenable
 * disparaîtrait de la sortie. La fixture est partagée avec la vue MCP ({@link ResumableRunFixtures},
 * test-jar de minos-bootstrap).
 */
class MinosCliRunnerResumeStatusTest {

    @Test
    void indexStatusThroughTheRunnerShowsTheInterruptedResumableRun(@TempDir Path temp) throws Exception {
        try (MinosApplication application = MinosApplication.open(temp.resolve("home"))) {
            UUID runId = ResumableRunFixtures.seedInterruptedResumableRun(application, temp);

            StringBuilder output = new StringBuilder();
            StringBuilder error = new StringBuilder();
            int exitCode = MinosCliRunner.run(application,
                    new String[]{"index-status", ResumableRunFixtures.PROJECT_NAME, "--format", "json"}, output, error);

            assertEquals(FindSymbolCommand.SUCCESS, exitCode, error.toString());
            assertTrue(output.toString().contains("\"resumableRunId\":\"" + runId + "\""), output.toString());
            assertTrue(output.toString().contains("\"resumableRunPhase\":\"PROVIDER_EXECUTION\""), output.toString());
            assertTrue(output.toString().contains("\"resumableTargets\":1"), output.toString());
        }
    }
}
