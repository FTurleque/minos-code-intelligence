package com.minos.storage.local.incremental;

import com.minos.incremental.ProjectFingerprint;
import com.minos.incremental.ProjectFingerprintService;
import com.minos.incremental.ProjectFingerprintSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Publication, promotion and compaction of fingerprint snapshots are mutually exclusive for one
 * project (lot 2, Q3). Every race is aligned on a {@link CyclicBarrier} and repeated
 * {@link #ROUNDS} times in the test itself; nothing waits on a delay.
 */
class FileProjectFingerprintSnapshotStoreConcurrencyTest {

    private static final int ROUNDS = 10;
    private static final int LARGE_SNAPSHOT_FILES = 1_000;

    private final ProjectFingerprintService fingerprintService = new ProjectFingerprintService();

    @Test
    void twoConcurrentPublicationsWithDifferentContentLeaveExactlyOneSnapshotForTheIdentifier(@TempDir Path root)
            throws Exception {
        ProjectFingerprint first = fingerprintOf(root.resolve("first"), 3, "first");
        ProjectFingerprint second = fingerprintOf(root.resolve("second"), 3, "second");
        FileProjectFingerprintSnapshotStore store = new FileProjectFingerprintSnapshotStore(root.resolve("storage"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                UUID projectId = UUID.randomUUID();
                CyclicBarrier start = new CyclicBarrier(2);
                Future<Outcome> a = pool.submit(() -> publishAfter(start, store, projectId, "index-1", first));
                Future<Outcome> b = pool.submit(() -> publishAfter(start, store, projectId, "index-1", second));
                List<Outcome> outcomes = List.of(a.get(), b.get());

                long published = outcomes.stream().filter(outcome -> outcome.refusal() == null).count();
                assertEquals(1, published,
                        "round " + round + ": one content wins, the other is refused as a different content: "
                                + outcomes);
                ProjectFingerprintSnapshot winner = outcomes.stream()
                        .map(Outcome::snapshot).filter(java.util.Objects::nonNull).findFirst().orElseThrow();
                assertEquals(winner, store.load(projectId, "index-1").orElseThrow(),
                        "round " + round + ": the identifier resolves to the winner, never to two snapshots");
                store.promote(projectId, "index-1");
                assertEquals(winner, store.loadActive(projectId).orElseThrow());
                assertEquals(List.of("index-1"), store.listIndexSnapshotIds(projectId));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twoConcurrentPublicationsOfTheSameContentBothSucceedAndShareOneFile(@TempDir Path root) throws Exception {
        ProjectFingerprint fingerprint = fingerprintOf(root.resolve("content"), 3, "same");
        FileProjectFingerprintSnapshotStore store = new FileProjectFingerprintSnapshotStore(root.resolve("storage"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                UUID projectId = UUID.randomUUID();
                CyclicBarrier start = new CyclicBarrier(2);
                Future<Outcome> a = pool.submit(() -> publishAfter(start, store, projectId, "index-1", fingerprint));
                Future<Outcome> b = pool.submit(() -> publishAfter(start, store, projectId, "index-1", fingerprint));
                Outcome first = a.get();
                Outcome second = b.get();

                assertTrue(first.refusal() == null && second.refusal() == null,
                        "round " + round + ": a republication of identical content is idempotent: "
                                + List.of(first, second));
                assertEquals(first.snapshot(), second.snapshot());
                assertEquals(List.of("index-1"), store.listIndexSnapshotIds(projectId));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aCompactionNeverDeletesTheSnapshotAPromotionIsReadingOrLeavesADanglingPointer(@TempDir Path root)
            throws Exception {
        ProjectFingerprint large = fingerprintOf(root.resolve("large"), LARGE_SNAPSHOT_FILES, "large");
        FileProjectFingerprintSnapshotStore store = new FileProjectFingerprintSnapshotStore(root.resolve("storage"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                UUID projectId = UUID.randomUUID();
                store.publish(projectId, "active", large);
                store.promote(projectId, "active");
                store.publish(projectId, "candidate", large);
                CyclicBarrier start = new CyclicBarrier(2);
                Future<Outcome> promotion = pool.submit(() -> {
                    start.await();
                    try {
                        store.promote(projectId, "candidate");
                        return Outcome.published(null);
                    } catch (IOException refused) {
                        return Outcome.refused(refused);
                    }
                });
                Future<Outcome> compaction = pool.submit(() -> {
                    start.await();
                    store.compact(projectId, Set.of(), 0);
                    return Outcome.published(null);
                });
                Outcome promoted = promotion.get();
                compaction.get();

                ProjectFingerprintSnapshot active = store.loadActive(projectId).orElseThrow();
                if (promoted.refusal() == null) {
                    assertEquals("candidate", active.indexSnapshotId(),
                            "round " + round + ": a promotion that returned is the active snapshot");
                } else {
                    assertTrue(promoted.refusal().getMessage().contains("not published"),
                            "round " + round + ": a refused promotion is refused cleanly: " + promoted);
                    assertEquals("active", active.indexSnapshotId(),
                            "round " + round + ": a refused promotion leaves the previous active snapshot");
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static Outcome publishAfter(
            CyclicBarrier start,
            FileProjectFingerprintSnapshotStore store,
            UUID projectId,
            String indexSnapshotId,
            ProjectFingerprint fingerprint
    ) throws Exception {
        start.await();
        try {
            return Outcome.published(store.publish(projectId, indexSnapshotId, fingerprint));
        } catch (IOException refused) {
            return Outcome.refused(refused);
        }
    }

    private ProjectFingerprint fingerprintOf(Path directory, int files, String salt) throws IOException {
        Files.createDirectories(directory.resolve("src"));
        Files.writeString(directory.resolve("pom.xml"), "<project/>");
        for (int index = 0; index < files; index++) {
            Files.writeString(directory.resolve("src/File" + index + ".java"),
                    "class File" + index + " { String salt = \"" + salt + "\"; }");
        }
        return fingerprintService.capture(directory);
    }

    private record Outcome(ProjectFingerprintSnapshot snapshot, IOException refusal) {
        static Outcome published(ProjectFingerprintSnapshot snapshot) {
            return new Outcome(snapshot, null);
        }

        static Outcome refused(IOException refusal) {
            return new Outcome(null, refusal);
        }

        @Override
        public String toString() {
            return refusal == null ? "done" : "refused: " + refusal.getMessage();
        }
    }
}
