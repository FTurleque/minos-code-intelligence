package com.minos.storage.local;

import com.minos.incremental.ProjectFingerprint;
import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.ProjectIndexState;
import com.minos.storage.PersistentRetentionPolicy;
import com.minos.storage.StorageRetentionService;
import com.minos.storage.local.incremental.FileProjectFingerprintSnapshotStore;
import com.minos.storage.local.orchestration.FileIndexStateStore;
import com.minos.storage.local.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q4 (lot 2): can retention delete a snapshot that is prepared and not yet promoted? These tests
 * place the retention pass at the only harmful point of a lifecycle, between the publication of the
 * fingerprint snapshot and its promotion, with the protected set a retention computed before the
 * lifecycle promoted anything (the state and the structural snapshot still name the previous
 * snapshot). They are deterministic: the interleaving is the order of the calls, nothing waits.
 *
 * <p>Verdict recorded in FIAB-SUIVI section 8: under the production policy the prepared snapshot is
 * the newest file of its directory and is kept, whatever the protected set says; the scenario is not
 * reproducible, so retention was not changed. The structural snapshot a run prepares lives under
 * {@code staged-snapshots/}, which no retention walks.</p>
 */
class LocalStorageRetentionPreparedSnapshotTest {

    private static final Instant LONG_AGO = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void retentionRunningBetweenTheFingerprintPublicationAndItsPromotionKeepsThePreparedSnapshot(@TempDir Path home)
            throws Exception {
        Path knowledgeRoot = home.resolve("symbol-snapshots");
        Path indexRoot = home.resolve("index-state");
        Path fingerprintRoot = home.resolve("fingerprint-snapshots");
        FileSymbolSnapshotStore knowledge = new FileSymbolSnapshotStore(knowledgeRoot);
        FileIndexStateStore states = new FileIndexStateStore(indexRoot);
        FileProjectFingerprintSnapshotStore fingerprints = new FileProjectFingerprintSnapshotStore(fingerprintRoot);
        UUID projectId = UUID.randomUUID();
        ProjectFingerprint fingerprint = emptyFingerprint();

        for (int index = 0; index <= 4; index++) {
            fingerprints.publish(projectId, "snapshot-" + index, fingerprint);
        }
        fingerprints.promote(projectId, "snapshot-4");
        ageEveryFingerprintFile(fingerprintRoot.resolve(projectId.toString()));
        // The previous snapshot is the active one everywhere: the protected set a retention computes now.
        knowledge.publish(projectId, "snapshot-4", List.of(), List.of(), List.of());
        states.saveProjectState(new ProjectIndexState(projectId, ProjectIndexState.Availability.READY,
                Optional.of("snapshot-4"), Optional.empty(), Instant.parse("2026-09-30T08:00:00Z"),
                Optional.empty()));
        // A lifecycle in flight has published the baseline of its next snapshot and not promoted it yet.
        fingerprints.publish(projectId, "snapshot-5", fingerprint);

        StorageRetentionService.RetentionResult result;
        try (IndexStateStore.ProjectLease ignored = states.acquireProjectLease(projectId)) {
            result = new LocalStorageRetentionService(home, knowledgeRoot, indexRoot, fingerprints, states)
                    .compact(projectId, PersistentRetentionPolicy.DEFAULT);
        }

        assertTrue(result.deletedFingerprintSnapshots() > 0, "the pass is not vacuous: old history was reclaimed");
        assertTrue(fingerprints.load(projectId, "snapshot-5").isPresent(),
                "the prepared snapshot is the newest file: retention keeps it");
        fingerprints.promote(projectId, "snapshot-5");
        assertEquals("snapshot-5", fingerprints.loadActive(projectId).orElseThrow().indexSnapshotId());
    }

    @Test
    void theDocumentedLockOrderHoldsWithoutDeadlockLifecycleThenStructuralMutationThenRetention(@TempDir Path home)
            throws Exception {
        Path knowledgeRoot = home.resolve("symbol-snapshots");
        Path indexRoot = home.resolve("index-state");
        Path fingerprintRoot = home.resolve("fingerprint-snapshots");
        FileSymbolSnapshotStore knowledge = new FileSymbolSnapshotStore(knowledgeRoot);
        FileIndexStateStore states = new FileIndexStateStore(indexRoot);
        FileProjectFingerprintSnapshotStore fingerprints = new FileProjectFingerprintSnapshotStore(fingerprintRoot);
        LocalStorageRetentionService retention =
                new LocalStorageRetentionService(home, knowledgeRoot, indexRoot, fingerprints, states);
        UUID projectId = UUID.randomUUID();

        // One thread, the order of FIAB-SUIVI 8.6: the lifecycle lease first, then every mutation of
        // the stores (each takes and releases the mutation lease), then retention (which takes it twice
        // in sequence). A nested acquisition of the same lease would wait for its own thread until the
        // deadline and fail.
        try (IndexStateStore.ProjectLease lifecycle = states.acquireProjectLease(projectId)) {
            for (int index = 0; index < 4; index++) {
                knowledge.publish(projectId, "snapshot-" + index, List.of(), List.of(), List.of());
                fingerprints.publish(projectId, "snapshot-" + index, emptyFingerprint());
                fingerprints.promote(projectId, "snapshot-" + index);
            }
            retention.compact(projectId, PersistentRetentionPolicy.DEFAULT);
        }

        assertEquals("snapshot-3", knowledge.loadActiveKnowledge(projectId).orElseThrow().snapshotId());
        assertEquals("snapshot-3", fingerprints.loadActive(projectId).orElseThrow().indexSnapshotId());
    }

    @Test
    void theStructuralSnapshotARunPreparesIsOutsideEveryRetentionRootEvenForAPolicyThatKeepsNothing(@TempDir Path home)
            throws Exception {
        Path knowledgeRoot = home.resolve("symbol-snapshots");
        Path indexRoot = home.resolve("index-state");
        Path fingerprintRoot = home.resolve("fingerprint-snapshots");
        FileSymbolSnapshotStore knowledge = new FileSymbolSnapshotStore(knowledgeRoot);
        FileIndexStateStore states = new FileIndexStateStore(indexRoot);
        FileProjectFingerprintSnapshotStore fingerprints = new FileProjectFingerprintSnapshotStore(fingerprintRoot);
        UUID projectId = UUID.randomUUID();
        for (int index = 0; index < 3; index++) {
            knowledge.publish(projectId, "snapshot-" + index, List.of(), List.of(), List.of());
        }
        // What ScipProjectSnapshotLifecycle.stage writes: a private store under staged-snapshots/<runId>/project.
        Path stagedStoreRoot = home.resolve("staged-snapshots").resolve(UUID.randomUUID().toString()).resolve("project");
        FileSymbolSnapshotStore staged = new FileSymbolSnapshotStore(stagedStoreRoot);
        staged.publish(projectId, "run-prepared", List.of(), List.of(), List.of());
        long preparedFilesBefore = countFiles(home.resolve("staged-snapshots"));

        new LocalStorageRetentionService(home, knowledgeRoot, indexRoot, fingerprints, states)
                .compact(projectId, new PersistentRetentionPolicy(0, 0, 0));
        knowledge.retentionService().deleteOrphanPreparedSnapshots(
                projectId, Instant.now().plusSeconds(3L * 24L * 3600L), java.time.Duration.ofHours(1));

        assertTrue(preparedFilesBefore > 0);
        assertEquals(preparedFilesBefore, countFiles(home.resolve("staged-snapshots")),
                "no retention walks staged-snapshots: a prepared structural snapshot is never reclaimed by one");
        assertEquals("run-prepared", staged.loadActiveKnowledge(projectId).orElseThrow().snapshotId());
    }

    private static long countFiles(Path directory) throws IOException {
        try (var walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    private static void ageEveryFingerprintFile(Path projectDirectory) throws IOException {
        int index = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(projectDirectory, "fingerprint-*.bin")) {
            for (Path file : files) {
                Files.setLastModifiedTime(file, FileTime.from(LONG_AGO.plusSeconds(60L * index++)));
            }
        }
    }

    private static ProjectFingerprint emptyFingerprint() {
        String emptySha256 = "e3b0c44298fc1c149afbf4c8996fb924"
                + "27ae41e4649b934ca495991b7852b855";
        return new ProjectFingerprint(emptySha256, emptySha256, List.of());
    }
}
