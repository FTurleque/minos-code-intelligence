package com.minos.cli;

import com.minos.application.LocalProjectOperations;
import com.minos.application.MinosApplication;
import com.minos.application.ProjectOperations;
import com.minos.testsupport.LogCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q14 : l'historique de la CLI ({@code cli-index-history}) est une preuve secondaire, l'instantané actif fait
 * foi. Quand son écriture échoue après la validation de l'import, l'import reste réussi (le flux ne change pas)
 * mais l'échec cesse d'être silencieux : une trace WARNING l'annonce, sans chemin ni message d'exception.
 */
class ImportHistoryFailureTest {

    private static final Path FIXTURE = Path.of("fixtures", "typescript", "typescript-modules");
    private static final Path SCIP = FIXTURE.resolve(Path.of(".minos-m0", "scip-typescript", "index.scip"));

    @Test
    void aFailedHistoryWriteIsJournaledAndTheImportStillSucceeds(@TempDir Path home) throws Exception {
        try (MinosApplication application = MinosApplication.open(home);
             LogCapture logs = new LogCapture(LocalProjectOperations.class)) {
            LocalProjectOperations operations = new LocalProjectOperations(application);
            ProjectOperations.ProjectView project = operations.addProject(FIXTURE, "ts");
            // Un fichier à la place du répertoire d'historique : l'écriture de l'historique échoue.
            Files.writeString(home.resolve("cli-index-history"), "not a directory");

            ProjectOperations.IndexImportResult result =
                    operations.importScip("ts", SCIP, "scip-typescript", "0.4.0", null, null);

            assertEquals(ProjectOperations.IndexImportCommitStatus.COMMITTED, result.commitStatus());
            assertTrue(result.normalizedSymbolCount() > 0);
            assertEquals("READY", operations.inspectProject("ts").indexState());
            List<String> warnings = logs.warnings();
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.getFirst().contains(project.id()), warnings.getFirst());
            assertTrue(warnings.getFirst().contains("active snapshot"), warnings.getFirst());
            assertFalse(warnings.getFirst().contains(home.toString()), "aucun chemin absolu : " + warnings.getFirst());
            assertFalse(warnings.getFirst().contains("cli-index-history"), warnings.getFirst());
        }
    }

    @Test
    void aSuccessfulHistoryWriteStaysSilent(@TempDir Path home) throws Exception {
        try (MinosApplication application = MinosApplication.open(home);
             LogCapture logs = new LogCapture(LocalProjectOperations.class)) {
            LocalProjectOperations operations = new LocalProjectOperations(application);
            operations.addProject(FIXTURE, "ts");

            ProjectOperations.IndexImportResult result =
                    operations.importScip("ts", SCIP, "scip-typescript", "0.4.0", null, null);

            assertEquals(ProjectOperations.IndexImportCommitStatus.COMMITTED, result.commitStatus());
            assertTrue(Files.isRegularFile(
                    home.resolve("cli-index-history").resolve(result.projectId() + ".properties")));
            assertEquals(List.of(), logs.warnings());
        }
    }
}
