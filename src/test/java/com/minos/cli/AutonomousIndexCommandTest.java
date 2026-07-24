package com.minos.cli;

import com.minos.orchestration.IndexingMode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutonomousIndexCommandTest {

    @Test
    void dryRunUsesAutonomousPlanWithoutRequiringScipArtifact() throws Exception {
        AutonomousIndexOperations autonomous = new StubAutonomous();
        IndexCommand command = new IndexCommand(new StubProjects(), autonomous);
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();

        int code = command.run(new String[]{"demo", "--dry-run", "--format", "json"}, output, error);

        assertEquals(0, code);
        assertTrue(error.isEmpty());
        assertTrue(output.toString().contains("\"mode\":\"FULL\""));
        assertTrue(output.toString().contains("\"providers\":[\"scip-java\"]"));
    }

    private static final class StubAutonomous implements AutonomousIndexOperations {
        @Override
        public IndexPlanView plan(String projectIdentifier, String providerOverride, boolean forceFull) {
            return plan();
        }

        @Override
        public IndexExecutionView execute(String projectIdentifier, String providerOverride, boolean forceFull) {
            return new IndexExecutionView(plan(), "run-1", "SUCCEEDED", "snapshot-1", true, null);
        }

        @Override
        public List<ProviderView> providers() {
            return List.of(new ProviderView("scip-java", "1", "READY", "cs", List.of()));
        }

        @Override
        public ProviderView installProvider(String providerId) {
            return providers().getFirst();
        }

        private static IndexPlanView plan() {
            return new IndexPlanView(
                    "project-id", "demo", "C:/demo",
                    List.of("JAVA"), List.of("MAVEN"), List.of("scip-java"),
                    List.of(new ProviderView("scip-java", "1", "READY", "cs", List.of())),
                    IndexingMode.FULL, List.of("NO_ACTIVE_INDEX"), List.of(), false);
        }
    }

    private static final class StubProjects implements ProjectOperations {
        @Override public ProjectView addProject(Path rootPath, String displayName) { throw new UnsupportedOperationException(); }
        @Override public List<ProjectView> listProjects() { return List.of(); }
        @Override public ProjectView inspectProject(String projectIdentifier) { throw new UnsupportedOperationException(); }
        @Override public IndexImportResult importScip(
                String projectIdentifier, Path indexFile, String providerId,
                String providerVersion, String moduleId, String snapshotId) {
            throw new UnsupportedOperationException();
        }
    }
}
