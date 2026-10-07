package com.minos.cli;

import com.minos.application.ProjectOperations;
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

    /** MINOS-AUD-C03 : le statut demande la vue sans découverte, l'inspection demande la vue complète. */
    @Test
    void indexStatusAsksForTheStatusInspectionAndInspectAsksForTheFullOne() throws Exception {
        CountingOperations operations = new CountingOperations();
        ProjectCommand command = new ProjectCommand(operations, projectId -> Optional.empty());

        assertEquals(FindSymbolCommand.SUCCESS,
                command.runIndexStatus(new String[]{"demo"}, new StringBuilder(), new StringBuilder()));
        assertEquals(1, operations.statusInspections);
        assertEquals(0, operations.fullInspections, "a status must not ask for the structure of the repository");

        assertEquals(FindSymbolCommand.SUCCESS,
                command.runInspectAlias(new String[]{"demo"}, new StringBuilder(), new StringBuilder()));
        assertEquals(1, operations.statusInspections);
        assertEquals(1, operations.fullInspections);
    }

    private static final class CountingOperations extends StubProjectOperations {
        private int statusInspections;
        private int fullInspections;

        @Override public ProjectInspection inspection(String projectIdentifier) {
            fullInspections++;
            return new ProjectInspection(inspectProject(projectIdentifier), List.of());
        }

        @Override public ProjectInspection statusInspection(String projectIdentifier) {
            statusInspections++;
            return new ProjectInspection(inspectProject(projectIdentifier), List.of());
        }
    }

    private static class StubProjectOperations implements ProjectOperations {
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
