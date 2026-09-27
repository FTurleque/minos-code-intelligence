package com.minos.cli;

import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.ResumableRunSummary;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R1 lot 2 : `minos index-status` expose le run reprenable en texte et en JSON. */
class ProjectCommandResumeStatusTest {

    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();

    @Test
    void indexStatusRendersTheResumableRunInTextAndJson() throws Exception {
        ResumableRunSummary summary = new ResumableRunSummary(RUN_ID, IndexingRun.Phase.PROVIDER_EXECUTION,
                Instant.now().minusSeconds(90), 3, 4);
        ProjectCommand command = new ProjectCommand(new StubProjectOperations(), projectId -> Optional.of(summary));

        StringBuilder text = new StringBuilder();
        assertEquals(FindSymbolCommand.SUCCESS, command.runIndexStatus(new String[]{"demo"}, text, new StringBuilder()));
        assertTrue(text.toString().contains("resumableRunId: " + RUN_ID), text.toString());
        assertTrue(text.toString().contains("resumableTargets: 3/4"), text.toString());
        assertTrue(text.toString().contains("resumableCheckpointAgeSeconds: "), text.toString());

        StringBuilder json = new StringBuilder();
        assertEquals(FindSymbolCommand.SUCCESS, command.runIndexStatus(
                new String[]{"demo", "--format", "json"}, json, new StringBuilder()));
        assertTrue(json.toString().contains("\"resumableRunId\":\"" + RUN_ID + "\""), json.toString());
        assertTrue(json.toString().contains("\"resumableTargets\":3"), json.toString());
        assertTrue(json.toString().contains("\"resumableRunPhase\":\"PROVIDER_EXECUTION\""), json.toString());
    }

    @Test
    void indexStatusWithoutResumableRunRendersNone() throws Exception {
        ProjectCommand command = new ProjectCommand(new StubProjectOperations(), projectId -> Optional.empty());

        StringBuilder text = new StringBuilder();
        command.runIndexStatus(new String[]{"demo"}, text, new StringBuilder());
        assertTrue(text.toString().contains("resumableRunId: none"), text.toString());

        StringBuilder json = new StringBuilder();
        command.runIndexStatus(new String[]{"demo", "--format", "json"}, json, new StringBuilder());
        assertTrue(json.toString().contains("\"resumableRunId\":null"), json.toString());
    }

    private static final class StubProjectOperations implements ProjectOperations {
        @Override public ProjectView addProject(Path rootPath, String displayName) {
            throw new UnsupportedOperationException();
        }
        @Override public List<ProjectView> listProjects() { return List.of(view()); }
        @Override public ProjectView inspectProject(String projectIdentifier) { return view(); }
        @Override public IndexImportResult importScip(String projectIdentifier, Path indexFile, String providerId,
                                                      String providerVersion, String moduleId, String snapshotId) {
            throw new UnsupportedOperationException();
        }
        private static ProjectView view() {
            return new ProjectView(PROJECT_ID.toString(), "demo", "project-root", true, List.of("JAVA"),
                    List.of("MAVEN"), 1, "STALE", "snapshot-old", "2026-09-26T08:00:00Z", "scip-java", "scip-java@0.10.0");
        }
    }
}
