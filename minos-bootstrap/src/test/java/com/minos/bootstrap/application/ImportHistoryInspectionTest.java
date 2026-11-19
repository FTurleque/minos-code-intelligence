package com.minos.bootstrap.application;

import com.minos.application.LocalProjectOperations;
import com.minos.application.MinosApplication;
import com.minos.application.ProjectOperations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The import history crosses the port end to end: written by an import through the production composition root,
 * then read back by the inspection of the project.
 */
class ImportHistoryInspectionTest {

    private static final Path FIXTURE = Path.of("fixtures", "typescript", "typescript-modules");
    private static final Path SCIP = FIXTURE.resolve(Path.of(".minos-m0", "scip-typescript", "index.scip"));

    @Test
    void theInspectionAfterAnImportShowsItsSnapshotProviderVersionAndDate(@TempDir Path home) throws Exception {
        Instant before = Instant.now();
        try (MinosApplication application = MinosApplication.open(home)) {
            LocalProjectOperations operations = new LocalProjectOperations(application);
            operations.addProject(FIXTURE, "ts");

            ProjectOperations.IndexImportResult result =
                    operations.importScip("ts", SCIP, "scip-typescript", "0.4.0", null, null);
            ProjectOperations.ProjectView view = operations.inspectProject("ts");

            assertEquals(result.snapshotId(), view.activeSnapshotId());
            assertEquals("scip-typescript", view.providerId());
            assertEquals("0.4.0", view.providerVersion());
            Instant completedAt = Instant.parse(view.lastSuccessfulIndexAt());
            assertFalse(completedAt.isBefore(before), view.lastSuccessfulIndexAt());
            assertTrue(Files.isRegularFile(
                    home.resolve("cli-index-history").resolve(result.projectId() + ".properties")));
        }
    }
}
