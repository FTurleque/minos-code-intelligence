package com.minos.application.dynamic;

import com.minos.dynamic.RuntimeObservationSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The envelope is read from one open, never from a pathname checked and then opened again: a leaf
 * that flips between a regular file and a link to a foreign envelope must never yield the foreign
 * envelope. The scenario is a race by nature (the primitive's own deterministic swap test lives in
 * {@code com.minos.io.OpenRegularFileNoFollowTest}); this one drives the real codec through it.
 */
class RuntimeObservationEnvelopeCodecLinkSwapTest {

    private static final long RACE_MILLIS = 4_000L;

    @Test
    void aLeafFlippingToALinkNeverYieldsTheForeignEnvelope(@TempDir Path root) throws Exception {
        UUID project = UUID.randomUUID();
        Path benign = Files.writeString(root.resolve("benign.tsv"),
                envelope(project, "benign-session"), StandardCharsets.UTF_8);
        Path foreign = Files.writeString(root.resolve("foreign.tsv"),
                envelope(project, "FOREIGN-session"), StandardCharsets.UTF_8);
        Path target = root.resolve("input.tsv");
        Files.copy(benign, target);
        RuntimeObservationEnvelopeCodec codec = new RuntimeObservationEnvelopeCodec();

        AtomicBoolean stop = new AtomicBoolean();
        Thread flipper = Thread.ofPlatform().start(() -> {
            Path stagedLink = root.resolve("staged.lnk");
            Path stagedFile = root.resolve("staged.tsv");
            while (!stop.get()) {
                try {
                    Files.createSymbolicLink(stagedLink, foreign);
                    Files.move(stagedLink, target, StandardCopyOption.REPLACE_EXISTING);
                    Files.copy(benign, stagedFile, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(stagedFile, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException transientFailure) {
                    // A reader holding the leaf open can make one replacement fail on Windows: retry.
                    deleteQuietly(stagedLink);
                    deleteQuietly(stagedFile);
                }
            }
        });
        long deadline = System.nanoTime() + RACE_MILLIS * 1_000_000L;
        String leaked = null;
        long reads = 0L;
        try {
            while (System.nanoTime() < deadline && leaked == null) {
                try {
                    RuntimeObservationSession session = codec.read(target).session();
                    reads++;
                    if (session.sessionId().startsWith("FOREIGN")) leaked = session.sessionId();
                } catch (IOException refused) {
                    // A link caught by the open is refused: that is the correct outcome.
                }
            }
        } finally {
            stop.set(true);
            flipper.join();
        }

        assertFalse(leaked != null, "the codec followed a link swapped in after the check (" + reads + " reads)");
    }

    @Test
    void aLinkToAnEnvelopeIsRefusedWithoutRevealingThePath(@TempDir Path root) throws Exception {
        UUID project = UUID.randomUUID();
        Path foreign = Files.writeString(root.resolve("foreign.tsv"),
                envelope(project, "FOREIGN-session"), StandardCharsets.UTF_8);
        Path link = root.resolve("input.tsv");
        Files.createSymbolicLink(link, foreign);

        IOException refused = assertThrows(IOException.class, () -> new RuntimeObservationEnvelopeCodec().read(link));
        assertEquals("runtime observation input must be a regular non-symlink file", refused.getMessage());
    }

    @Test
    void anAbsentOrNullInputIsRefusedWithTheSameMessage(@TempDir Path root) {
        RuntimeObservationEnvelopeCodec codec = new RuntimeObservationEnvelopeCodec();
        assertEquals("runtime observation input must be a regular non-symlink file",
                assertThrows(IOException.class, () -> codec.read(root.resolve("absent.tsv"))).getMessage());
        assertEquals("runtime observation input must be a regular non-symlink file",
                assertThrows(IOException.class, () -> codec.read(null)).getMessage());
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort: the next iteration recreates it.
        }
    }

    private static String envelope(UUID projectId, String sessionId) {
        return String.join("\n",
                RuntimeObservationSession.FORMAT,
                "session\t" + sessionId,
                "project\t" + projectId,
                "snapshot\tsnapshot-1",
                "started\t2026-07-29T06:00:00Z",
                "ended\t2026-07-29T06:05:00Z",
                "collector\tfixture\t1.0.0",
                "environment\ttest",
                "completeness\tPARTIAL",
                "symbol\tkey:service\tcom.acme.Service\tsrc/Service.java\t10\t5\t500",
                "");
    }
}
