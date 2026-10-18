package com.minos.cli.operations;

import com.minos.cli.support.CliCommandSupport;
import com.minos.diagnostics.PublicErrorMessages;
import com.minos.orchestration.IndexingRun;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La ligne publique d'un run d'indexation en échec nomme le run, sa phase et sa cause quand elle est sûre ; une cause
 * qui cite un chemin absolu ou un secret est remplacée par l'emplacement de l'enregistrement du run, jamais la ligne
 * entière par le nom de la classe d'exception.
 */
class IndexRunFailureMessageTest {

    private static final String ABSOLUTE_CAUSE = "IllegalStateException: provider exited with code 1; see "
            + "C:\\Users\\someone\\AppData\\Local\\MINOS\\data\\runs\\r\\scip-java\\provider.stderr.log";

    @Test
    void uneCauseQuiCiteUnCheminAbsoluEstRemplaceeParLEmplacementDuRun() {
        IndexingRun run = run(IndexingRun.Status.FAILED, IndexingRun.Phase.PROVIDER_EXECUTION, ABSOLUTE_CAUSE);

        String line = LocalAutonomousIndexOperations.failureMessage(run);

        assertTrue(line.contains(run.id().toString()), line);
        assertTrue(line.contains("PROVIDER_EXECUTION"), line);
        assertTrue(line.contains("MINOS_HOME/index-state/runs"), line);
        assertFalse(line.contains("C:\\Users"), line);
        assertFalse(PublicErrorMessages.looksSensitive(line), line);
        // La CLI publie la ligne telle quelle, au lieu de la remplacer par « IllegalStateException ».
        assertEquals(line, CliCommandSupport.failureMessage(new IllegalStateException(line)));
    }

    @Test
    void uneCauseSureEstJointeTelleQuelle() {
        String cause = "IllegalStateException: provider exited with code 1; see "
                + "runs/r/scip-java/provider.stderr.log under MINOS_HOME";
        IndexingRun run = run(IndexingRun.Status.FAILED, IndexingRun.Phase.PROVIDER_EXECUTION, cause);

        String line = LocalAutonomousIndexOperations.failureMessage(run);

        assertTrue(line.endsWith(cause), line);
        assertEquals(line, CliCommandSupport.failureMessage(new IllegalStateException(line)));
    }

    @Test
    void uneCauseQuiContientUnSecretNEstPasPubliee() {
        IndexingRun run = run(IndexingRun.Status.FAILED, IndexingRun.Phase.PROMOTION,
                "IOException: connection refused with password=hunter2");

        String line = LocalAutonomousIndexOperations.failureMessage(run);

        assertFalse(line.contains("hunter2"), line);
        assertTrue(line.contains("MINOS_HOME/index-state/runs"), line);
        assertEquals(line, CliCommandSupport.failureMessage(new IllegalStateException(line)));
    }

    @Test
    void unRunInterrompuResteAnnonceCommeRepriseable() {
        IndexingRun run = run(IndexingRun.Status.INTERRUPTED, IndexingRun.Phase.PROVIDER_EXECUTION, ABSOLUTE_CAUSE);

        String line = LocalAutonomousIndexOperations.failureMessage(run);

        assertTrue(line.contains("was interrupted and can be resumed"), line);
        assertFalse(PublicErrorMessages.looksSensitive(line), line);
    }

    private static IndexingRun run(IndexingRun.Status status, IndexingRun.Phase phase, String message) {
        Instant at = Instant.parse("2026-10-17T08:00:00Z");
        return new IndexingRun(UUID.randomUUID(), UUID.randomUUID(), status, phase, at, Optional.of(at), List.of(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(message));
    }
}
