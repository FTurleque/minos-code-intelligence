package com.minos.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportScipCommitStatusTest {

    @Test
    void committedSnapshotWithPendingMetadataReturnsSuccessAndExplicitWarning() throws Exception {
        ProjectOperations operations = new PendingMetadataOperations();
        ImportScipCommand command = new ImportScipCommand(operations);
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();

        int exit = command.run(new String[]{
                "demo", "--file", "index.scip", "--provider", "scip-java"
        }, output, error);

        assertEquals(FindSymbolCommand.SUCCESS, exit);
        assertTrue(output.toString().contains("commitStatus: COMMITTED_METADATA_PENDING"));
        assertTrue(error.toString().contains("snapshot committed but project metadata recovery is pending"));
        assertFalse(error.toString().contains("import-scip failed"));
    }

    private static final class PendingMetadataOperations implements ProjectOperations {
        @Override
        public ProjectView addProject(Path rootPath, String displayName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ProjectView> listProjects() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProjectView inspectProject(String projectIdentifier) {
            throw new UnsupportedOperationException();
        }

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
                    "00000000-0000-0000-0000-000000000001",
                    "snapshot-committed",
                    providerId,
                    providerVersion,
                    10,
                    20,
                    30,
                    4,
                    0,
                    0,
                    "2026-08-14T21:30:00Z",
                    IndexImportCommitStatus.COMMITTED_METADATA_PENDING,
                    "state store unavailable");
        }
    }
}
