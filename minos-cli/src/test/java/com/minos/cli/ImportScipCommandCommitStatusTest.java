package com.minos.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportScipCommandCommitStatusTest {

    @Test
    void committedImportWithMetadataRecoveryNeededRemainsSuccessfulAndExplicit(@TempDir Path temp) throws Exception {
        ProjectOperations operations = new ProjectOperations() {
            @Override public ProjectView addProject(Path rootPath, String displayName) { throw new UnsupportedOperationException(); }
            @Override public List<ProjectView> listProjects() { return List.of(); }
            @Override public ProjectView inspectProject(String projectIdentifier) { throw new UnsupportedOperationException(); }
            @Override
            public IndexImportResult importScip(
                    String projectIdentifier,
                    Path indexFile,
                    String providerId,
                    String providerVersion,
                    String moduleId,
                    String snapshotId
            ) {
                return new IndexImportResult(
                        "project-id",
                        "snapshot-1",
                        "scip-java",
                        "1.0",
                        10,
                        20,
                        30,
                        4,
                        1,
                        2,
                        true,
                        "2026-08-14T20:00:00Z");
            }
        };
        ImportScipCommand command = new ImportScipCommand(operations);
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();

        int code = command.run(new String[]{
                "demo",
                "--file", temp.resolve("index.scip").toString(),
                "--provider", "scip-java",
                "--format", "json"
        }, output, error);

        assertEquals(FindSymbolCommand.SUCCESS, code);
        assertTrue(output.toString().contains("\"metadataReconciliationRequired\":true"));
        assertTrue(error.toString().contains("snapshot committed"));
        assertTrue(error.toString().contains("metadata reconciliation is required"));
    }
}
