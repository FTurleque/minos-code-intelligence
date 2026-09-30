package com.minos.orchestration;

import com.minos.orchestration.IndexingRuntimePorts.ExecutionPathAuthorization;
import com.minos.testsupport.LogCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q14 : {@code tryCapture} rend « pas d'autorisation » quand les racines d'exécution ne peuvent pas être résolues
 * (chemins non matérialisés des tests de contrat, racine supprimée) : les ports qui n'exécutent pas de processus
 * continuent avec le contrat historique, et le lancement d'un processus local refuse l'absence d'autorisation
 * (choix conservé : propager casserait ces ports). La cause n'est en revanche plus perdue : elle est journalisée,
 * puisque le refus au lancement n'en porte aucune (« not canonically authorized before launch »). Aucun chemin,
 * aucun message d'exception dans la trace.
 */
class ExecutionPathAuthorizationDiagnosticsTest {

    @Test
    void unresolvableRootsGiveNoAuthorizationAndAreJournaled(@TempDir Path temporary) {
        Path missingRegistered = temporary.resolve("not-materialized");
        Path missingProject = missingRegistered.resolve("module");

        try (LogCapture logs = new LogCapture(ExecutionPathAuthorization.class)) {
            Optional<ExecutionPathAuthorization> captured =
                    ExecutionPathAuthorization.tryCapture(missingRegistered, missingProject);

            // Le flux ne change pas : pas d'autorisation, pas d'exception.
            assertEquals(Optional.empty(), captured);
            List<String> warnings = logs.warnings();
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.getFirst().contains("NoSuchFileException"), warnings.getFirst());
            assertTrue(warnings.getFirst().contains("refused"), warnings.getFirst());
            assertFalse(warnings.getFirst().contains(temporary.toString()), "aucun chemin absolu : " + warnings.getFirst());
            assertFalse(warnings.getFirst().contains("not-materialized"), warnings.getFirst());
        }
    }

    @Test
    void resolvableRootsStaySilent(@TempDir Path temporary) throws Exception {
        Path project = Files.createDirectory(temporary.resolve("project"));
        Path module = Files.createDirectory(project.resolve("module"));

        try (LogCapture logs = new LogCapture(ExecutionPathAuthorization.class)) {
            ExecutionPathAuthorization.tryCapture(project, module);

            assertEquals(List.of(), logs.warnings());
        }
    }
}
