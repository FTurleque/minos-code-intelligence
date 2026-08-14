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
        assertCommittedWarning(
                ProjectOperations.IndexImportCommitStatus.COMMITTED_METADATA_PENDING,
                "snapshot committed but project metadata recovery is pending");
    }

    @Test
    void committedSnapshotWithPendingDurabilityReturnsSuccessAndExplicitWarning() throws Exception {
        assertCommittedWarning(
                ProjectOperations.IndexImportCommitStatus.COMMITTED_DURABILITY_PENDING,
                "snapshot committed and authoritative but durability acknowledgement is pending");
    }

    @Test
    void committedSnapshotWithPendingDurabilityAndMetadataReturnsSuccessAndExplicitWarning() throws Exception {
        assertCommittedWarning(
                ProjectOperations.IndexImportCommitStatus.COMMITTED_DURABILITY_AND_METADATA_PENDING,
                "durability acknowledgement and metadata recovery are pending");
    }

    private static void assertCommittedWarning(
            ProjectOperations.IndexImportCommitStatus status,
            String expectedWarning
    ) throws Exception {
        ProjectOperations operations = new PendingOperations(status);
        ImportScipCommand command = new ImportScipCommand(operations);
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();

        int exit = command.run(new String[]{
                "demo", "--file", "index.scip", "--provider", "scip-java"
        }, output, error);

        assertEquals(FindSymbolCommand.SUCCESS, exit);
        assertTrue(output.toString().contains("commitStatus: " + status.name()));
        assertTrue(error.toString().contains(expectedWarning));
        assertFalse(error.toString().contains("import-scip failed"));
    }

    private static final class PendingOperations implements ProjectOperations {
        private final IndexImportCommitStatus status;

        private PendingOperations(IndexImportCommitStatus status) {
            this.status = status;
        }

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
                    status,
                    "synthetic pending commit follow-up");
        }
    }
}
