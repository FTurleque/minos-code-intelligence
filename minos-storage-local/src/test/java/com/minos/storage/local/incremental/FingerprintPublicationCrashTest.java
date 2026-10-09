package com.minos.storage.local.incremental;

import com.minos.incremental.ProjectFingerprintService;
import com.minos.incremental.ProjectFingerprintSnapshot;
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
 * MINOS-AUD-H12 : un arrêt brutal pendant la publication et la promotion d'une empreinte de projet laisse toujours
 * une empreinte active valide, l'ancienne ou la nouvelle. Même méthode que {@code SnapshotPublicationCrashTest} :
 * un processus fils est tué à des fractions croissantes de la durée d'une publication complète.
 */
class FingerprintPublicationCrashTest {
    private static final int FILES = 3_000;
    private static final double[] KILL_FRACTIONS = {0.1, 0.3, 0.5, 0.7, 0.85, 0.95, 0.99};

    @Test
    void aFingerprintPublicationKilledAtAnyPointLeavesAValidActiveFingerprint(@TempDir Path temp) throws Exception {
        Path storage = temp.resolve("storage");
        Path source = temp.resolve("source");
        Files.createDirectories(source.resolve("src"));
        Files.writeString(source.resolve("pom.xml"), "<project/>");
        for (int index = 0; index < FILES; index++) {
            Files.writeString(source.resolve("src/File" + index + ".java"), "class File" + index + " {}");
        }
        UUID project = UUID.randomUUID();
        FileProjectFingerprintSnapshotStore store = new FileProjectFingerprintSnapshotStore(storage);
        store.publish(project, "base", new ProjectFingerprintService().capture(source));
        store.promote(project, "base");

        Duration full = timedPublication(storage, project, "calibration", source);
        String active = "calibration";
        int interrupted = 0;
        for (int round = 0; round < KILL_FRACTIONS.length; round++) {
            String candidate = "crash-" + round;
            Process child = start(storage, project, candidate, source, false);
            long delay = Math.max(1, (long) (full.toMillis() * KILL_FRACTIONS[round]));
            boolean finished = child.waitFor(delay, TimeUnit.MILLISECONDS);
            child.descendants().forEach(ProcessHandle::destroyForcibly);
            child.destroyForcibly();
            assertTrue(child.waitFor(60, TimeUnit.SECONDS), "killed child did not exit");

            ProjectFingerprintSnapshot reopened = new FileProjectFingerprintSnapshotStore(storage).loadActive(project)
                    .orElseThrow(() -> new AssertionError("no active fingerprint after a crash in " + candidate));
            assertTrue(Set.of(active, candidate).contains(reopened.indexSnapshotId()),
                    candidate + " (killed at " + delay + " ms, finished=" + finished + ") exposes "
                            + reopened.indexSnapshotId());
            assertEquals(FILES, reopened.fingerprint().files().size() - 1, "the active fingerprint is complete");
            if (!finished && !reopened.indexSnapshotId().equals(candidate)) interrupted++;
            active = reopened.indexSnapshotId();
        }
        assertTrue(interrupted > 0, "no kill landed before the end of a publication: the test would prove nothing");
    }

    private static Duration timedPublication(Path storage, UUID project, String id, Path source) throws Exception {
        long started = System.nanoTime();
        Process child = start(storage, project, id, source, true);
        assertTrue(child.waitFor(5, TimeUnit.MINUTES), "calibration publication timed out");
        String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, child.exitValue(), output);
        assertTrue(output.contains(FingerprintPublishChild.DONE), output);
        return Duration.ofNanos(System.nanoTime() - started);
    }

    private static Process start(Path storage, UUID project, String id, Path source, boolean pipe) throws IOException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        ProcessBuilder builder = new ProcessBuilder(List.of(java.toString(), "-cp",
                System.getProperty("java.class.path"), FingerprintPublishChild.class.getName(),
                storage.toString(), project.toString(), id, source.toString()))
                .redirectErrorStream(true);
        if (!pipe) builder.redirectOutput(Files.createTempFile("minos-fingerprint-child-", ".log").toFile());
        return builder.start();
    }
}
