package com.minos.storage.local.store;

import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-H12 : un arrêt brutal pendant la publication d'un snapshot local laisse toujours un snapshot actif
 * valide. Un processus fils publie un gros snapshot et est tué à des fractions croissantes de la durée d'une
 * publication complète ; après chaque arrêt, une instance neuve du magasin relit le snapshot actif, qui doit être
 * l'ancien ou le nouveau, entier et vérifié, jamais un état intermédiaire.
 */
class SnapshotPublicationCrashTest {
    private static final int BIG = 150_000;
    private static final double[] KILL_FRACTIONS = {0.05, 0.2, 0.4, 0.6, 0.8, 0.9, 0.97};

    @Test
    void aPublicationKilledAtAnyPointLeavesAValidActiveSnapshot(@TempDir Path root) throws Exception {
        UUID project = UUID.randomUUID();
        new FileSymbolSnapshotStore(root).publish(project, "base", SnapshotPublishChild.symbols(project, "base", 3));

        Duration full = timedPublication(root, project, "calibration");
        String active = "calibration";
        int interrupted = 0;
        for (int round = 0; round < KILL_FRACTIONS.length; round++) {
            String candidate = "crash-" + round;
            Process child = start(root, project, candidate);
            long delay = Math.max(1, (long) (full.toMillis() * KILL_FRACTIONS[round]));
            boolean finished = child.waitFor(delay, TimeUnit.MILLISECONDS);
            child.descendants().forEach(ProcessHandle::destroyForcibly);
            child.destroyForcibly();
            assertTrue(child.waitFor(60, TimeUnit.SECONDS), "killed child did not exit");

            CodeKnowledgeSnapshot reopened = new FileSymbolSnapshotStore(root).loadActiveKnowledge(project)
                    .orElseThrow(() -> new AssertionError("no active snapshot after a crash in round " + candidate));
            assertTrue(Set.of(active, candidate).contains(reopened.snapshotId()),
                    "round " + candidate + " (killed at " + delay + " ms, finished=" + finished
                            + ") exposes an unexpected snapshot: " + reopened.snapshotId());
            assertEquals(BIG, reopened.symbols().size(), "the active snapshot is complete");
            if (!finished && !reopened.snapshotId().equals(candidate)) interrupted++;
            active = reopened.snapshotId();
        }
        assertTrue(interrupted > 0, "no kill landed before the end of a publication: the test would prove nothing");
    }

    private static Duration timedPublication(Path root, UUID project, String snapshotId) throws Exception {
        long started = System.nanoTime();
        Process child = start(root, project, snapshotId);
        assertTrue(child.waitFor(5, TimeUnit.MINUTES), "calibration publication timed out");
        String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, child.exitValue(), output);
        assertTrue(output.contains(SnapshotPublishChild.DONE), output);
        return Duration.ofNanos(System.nanoTime() - started);
    }

    private static Process start(Path root, UUID project, String snapshotId) throws IOException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path log = Files.createTempFile("minos-snapshot-child-", ".log");
        return new ProcessBuilder(List.of(java.toString(), "-cp", System.getProperty("java.class.path"),
                SnapshotPublishChild.class.getName(), root.toString(), project.toString(), snapshotId,
                Integer.toString(BIG)))
                .redirectErrorStream(true)
                .redirectOutput(snapshotId.equals("calibration") ? ProcessBuilder.Redirect.PIPE
                        : ProcessBuilder.Redirect.to(log.toFile()))
                .start();
    }
}
