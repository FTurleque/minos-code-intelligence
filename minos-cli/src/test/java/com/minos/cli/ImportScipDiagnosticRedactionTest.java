package com.minos.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportScipDiagnosticRedactionTest {

    @Test
    void executionIllegalArgumentIsNotMisclassifiedAsUsageAndDoesNotLeakPath() throws Exception {
        ProjectOperations operations = new StubOperations() {
            @Override
            public IndexImportResult importScip(
                    String projectIdentifier,
                    Path indexFile,
                    String providerId,
                    String providerVersion,
                    String moduleId,
                    String snapshotId
            ) {
                throw new IllegalArgumentException(
                        "SCIP artifact must be an existing regular file: /home/private-user/repository/index.scip");
            }
        };
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();

        int exit = new ImportScipCommand(operations).run(new String[]{
                "demo", "--file", "index.scip", "--provider", "scip-java"
        }, output, error);

        assertEquals(FindSymbolCommand.EXECUTION_ERROR, exit);
        assertTrue(output.isEmpty());
        assertEquals("error: import-scip failed: IllegalArgumentException\n", error.toString());
        assertFalse(error.toString().contains("private-user"));
        assertFalse(error.toString().contains("Usage:"));
    }

    @Test
    void committedDiagnosticIsRedactedInJsonAndWarning() throws Exception {
        ProjectOperations operations = new StubOperations() {
            @Override
            public IndexImportResult importScip(
                    String projectIdentifier,
                    Path indexFile,
                    String providerId,
                    String providerVersion,
                    String moduleId,
                    String snapshotId
            ) {
                return result("metadata write failed at C:\\Users\\private-user\\.minos\\state.properties");
            }
        };
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();

        int exit = new ImportScipCommand(operations).run(new String[]{
                "demo", "--file", "index.scip", "--provider", "scip-java", "--format", "json"
        }, output, error);

        assertEquals(FindSymbolCommand.SUCCESS, exit);
        assertTrue(output.toString().contains("internal diagnostic redacted"), output.toString());
        assertTrue(error.toString().contains("internal diagnostic redacted"), error.toString());
        assertFalse(output.toString().contains("private-user"));
        assertFalse(error.toString().contains("private-user"));
    }

    private static ProjectOperations.IndexImportResult result(String diagnostic) {
        return new ProjectOperations.IndexImportResult(
                "00000000-0000-0000-0000-000000000001",
                "snapshot-committed",
                "scip-java",
                "1.0",
                10,
                20,
                30,
                4,
                0,
                0,
                "2026-08-16T16:00:00Z",
                ProjectOperations.IndexImportCommitStatus.COMMITTED_METADATA_PENDING,
                diagnostic);
    }

    private abstract static class StubOperations implements ProjectOperations {
        @Override public ProjectView addProject(Path rootPath, String displayName) { throw new UnsupportedOperationException(); }
        @Override public List<ProjectView> listProjects() { throw new UnsupportedOperationException(); }
        @Override public ProjectView inspectProject(String projectIdentifier) { throw new UnsupportedOperationException(); }
    }
}
