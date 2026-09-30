package com.minos.storage.local.orchestration;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexingRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q14 : à l'ouverture du magasin, un fichier de run hérité (à plat dans {@code runs/}) dont le nom n'est pas un
 * identifiant ou dont le contenu est corrompu est ignoré : il ne doit jamais empêcher l'ouverture ni la migration
 * des autres runs (choix conservé). Le silence est levé : une trace WARNING nomme le fichier (nom seul, pas de
 * chemin), la raison et la classe de l'exception, et reste bornée quand il y en a beaucoup.
 */
class LegacyRunMigrationDiagnosticsTest {

    @TempDir
    Path root;

    @Test
    void skippedLegacyFilesAreJournaledAndTheValidRunIsStillMigrated() throws Exception {
        Path stateRoot = root.resolve("state");
        UUID projectId = UUID.randomUUID();
        UUID validRun = UUID.randomUUID();
        legacyRun(stateRoot, projectId, validRun);
        UUID corruptRun = UUID.randomUUID();
        Path corrupt = Files.writeString(stateRoot.resolve("runs").resolve(corruptRun + ".properties"), "garbage=1\n");
        Path stray = Files.writeString(stateRoot.resolve("runs").resolve("notes.properties"), "x=1\n");

        try (LogCapture logs = new LogCapture(FileIndexStateStore.class)) {
            FileIndexStateStore reopened = new FileIndexStateStore(stateRoot);

            // Le flux ne change pas : l'ouverture aboutit, le run valide est migré, les fichiers ignorés restent en place.
            assertEquals(List.of(validRun), reopened.listRuns(projectId).stream().map(IndexingRun::id).toList());
            assertTrue(Files.isRegularFile(corrupt));
            assertTrue(Files.isRegularFile(stray));
            List<String> warnings = logs.warnings();
            assertEquals(2, warnings.size(), warnings.toString());
            String all = String.join("\n", warnings);
            assertTrue(all.contains("'" + corruptRun + ".properties'"), all);
            assertTrue(all.contains("'notes.properties'"), all);
            assertTrue(all.contains("IllegalStateException"), all);
            assertFalse(all.contains(root.toString()), "aucun chemin absolu : " + all);
            assertFalse(all.contains(validRun.toString()), "le run valide, migré, ne produit aucune trace : " + all);
        }
    }

    @Test
    void aStoreWithOnlyValidLegacyRunsMigratesThemInSilence() throws Exception {
        Path stateRoot = root.resolve("state");
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        legacyRun(stateRoot, projectId, runId);

        try (LogCapture logs = new LogCapture(FileIndexStateStore.class)) {
            new FileIndexStateStore(stateRoot);

            assertEquals(List.of(), logs.warnings());
        }
    }

    @Test
    void manySkippedLegacyFilesProduceABoundedNumberOfTraces() throws Exception {
        Path stateRoot = root.resolve("state");
        new FileIndexStateStore(stateRoot);
        for (int index = 0; index < 25; index++) {
            Files.writeString(stateRoot.resolve("runs").resolve(UUID.randomUUID() + ".properties"), "garbage=1\n");
        }

        try (LogCapture logs = new LogCapture(FileIndexStateStore.class)) {
            new FileIndexStateStore(stateRoot);

            List<String> warnings = logs.warnings();
            assertEquals(11, warnings.size(), "dix traces, plus une de synthèse : " + warnings);
            assertTrue(warnings.getLast().contains("25"), "la synthèse compte les fichiers ignorés : " + warnings.getLast());
        }
    }

    /** Un run au format historique : le fichier d'une partition, déplacé à plat dans {@code runs/}. */
    private static void legacyRun(Path stateRoot, UUID projectId, UUID runId) throws Exception {
        FileIndexStateStore initial = new FileIndexStateStore(stateRoot);
        initial.saveRun(new IndexingRun(
                runId, projectId, IndexingRun.Status.SUCCEEDED, IndexingRun.Phase.COMPLETED,
                Instant.EPOCH, Optional.of(Instant.EPOCH.plusSeconds(1)),
                List.of(new IndexingRun.IndexerExecution(Language.JAVA, "scip-java",
                        stateRoot.resolve("legacy.scip").toAbsolutePath().normalize())),
                Optional.of("snapshot-1"), Optional.empty(), Optional.of("snapshot-1"), Optional.of("completed")));
        Path partitioned = stateRoot.resolve("runs").resolve(projectId.toString()).resolve(runId + ".properties");
        Path legacy = stateRoot.resolve("runs").resolve(runId + ".properties");
        Files.move(partitioned, legacy);
        Files.deleteIfExists(partitioned.getParent());
    }
}
