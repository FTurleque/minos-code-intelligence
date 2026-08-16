package com.minos.cli;

import com.minos.orchestration.IndexingMode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliBoundaryHardeningTest {

    @Test
    void toolsExecutionIllegalArgumentIsAnExecutionFailureAndIsRedacted() throws Exception {
        AutonomousIndexOperations operations = new StubAutonomousOperations() {
            @Override
            public List<ProviderView> providers() {
                throw new IllegalArgumentException("provider state failed at C:\\Users\\private-user\\.minos\\tools");
            }
        };
        StringBuilder error = new StringBuilder();

        int exit = new ToolsCommand(operations).run(new String[]{"list"}, new StringBuilder(), error);

        assertEquals(FindSymbolCommand.EXECUTION_ERROR, exit);
        assertEquals("error: tools failed: IllegalArgumentException\n", error.toString());
        assertFalse(error.toString().contains("private-user"));
        assertFalse(error.toString().contains("Usage:"));
    }

    @Test
    void autonomousIndexDiagnosticIsSanitizedBeforeTextOutput() throws Exception {
        AutonomousIndexOperations operations = new StubAutonomousOperations() {
            @Override
            public IndexExecutionView execute(String projectIdentifier, String providerOverride, boolean forceFull) {
                return new IndexExecutionView(CliBoundaryHardeningTest.plan(), "run-1", "FAILED", null, false,
                        "worker failed at /home/private-user/.minos/runtime/worker.log");
            }
        };
        StringBuilder output = new StringBuilder();

        int exit = new IndexCommand(new StubProjectOperations(), operations)
                .run(new String[]{"demo"}, output, new StringBuilder());

        assertEquals(FindSymbolCommand.SUCCESS, exit);
        assertTrue(output.toString().contains("diagnostic: internal diagnostic redacted"), output.toString());
        assertFalse(output.toString().contains("private-user"));
    }

    private static AutonomousIndexOperations.IndexPlanView plan() {
        return new AutonomousIndexOperations.IndexPlanView(
                "00000000-0000-0000-0000-000000000001",
                "demo",
                "/workspace/demo",
                List.of("JAVA"),
                List.of("MAVEN"),
                List.of("scip-java"),
                List.of(),
                IndexingMode.FULL,
                List.of("test"),
                List.of(),
                false);
    }

    private abstract static class StubAutonomousOperations implements AutonomousIndexOperations {
        @Override public IndexPlanView plan(String projectIdentifier, String providerOverride, boolean forceFull) {
            return CliBoundaryHardeningTest.plan();
        }
        @Override public IndexExecutionView execute(String projectIdentifier, String providerOverride, boolean forceFull) {
            throw new UnsupportedOperationException();
        }
        @Override public List<ProviderView> providers() { return List.of(); }
        @Override public ProviderView installProvider(String providerId) { throw new UnsupportedOperationException(); }
    }

    private static final class StubProjectOperations implements ProjectOperations {
        @Override public ProjectView addProject(Path rootPath, String displayName) { throw new UnsupportedOperationException(); }
        @Override public List<ProjectView> listProjects() { throw new UnsupportedOperationException(); }
        @Override public ProjectView inspectProject(String projectIdentifier) { throw new UnsupportedOperationException(); }
        @Override public IndexImportResult importScip(
                String projectIdentifier,
                Path indexFile,
                String providerId,
                String providerVersion,
                String moduleId,
                String snapshotId
        ) { throw new UnsupportedOperationException(); }
    }
}
