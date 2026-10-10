package com.minos.orchestration;

import com.minos.incremental.ProjectFingerprint;
import com.minos.incremental.ProjectFingerprintService;
import com.minos.incremental.ProjectFingerprintSnapshot;
import com.minos.incremental.ProjectFingerprintSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The alignment rule on its own, against an in-memory store (the file-backed store is tested in minos-bootstrap). */
class ProjectFingerprintSnapshotAlignmentServiceTest {

    private static final Instant OBSERVED = Instant.parse("2026-07-23T00:00:00Z");

    @TempDir Path temporary;

    @Test
    void anActiveIndexWithoutAFingerprintBaselineHasNothingToAlign() throws Exception {
        ActiveOnlyStore store = new ActiveOnlyStore(Optional.empty());
        ProjectFingerprintSnapshotAlignmentService service = new ProjectFingerprintSnapshotAlignmentService(store);

        assertTrue(service.loadAlignedWithActiveIndex(ready(UUID.randomUUID(), "index-1")).isEmpty());
    }

    @Test
    void aFingerprintOfTheActiveIndexIsReturned() throws Exception {
        UUID projectId = UUID.randomUUID();
        ProjectFingerprintSnapshot snapshot = snapshot(projectId, "index-1");
        ProjectFingerprintSnapshotAlignmentService service =
                new ProjectFingerprintSnapshotAlignmentService(new ActiveOnlyStore(Optional.of(snapshot)));

        assertEquals(snapshot, service.loadAlignedWithActiveIndex(ready(projectId, "index-1")).orElseThrow());
    }

    @Test
    void aFingerprintOfAnotherIndexSnapshotIsRefused() throws Exception {
        UUID projectId = UUID.randomUUID();
        ProjectFingerprintSnapshotAlignmentService service = new ProjectFingerprintSnapshotAlignmentService(
                new ActiveOnlyStore(Optional.of(snapshot(projectId, "index-1"))));

        IOException refused = assertThrows(IOException.class,
                () -> service.loadAlignedWithActiveIndex(ready(projectId, "index-2")));
        assertTrue(refused.getMessage().contains("not aligned"), refused.getMessage());
    }

    @Test
    void anActiveFingerprintWithoutAnActiveIndexIsRefused() throws Exception {
        UUID projectId = UUID.randomUUID();
        ProjectFingerprintSnapshotAlignmentService service = new ProjectFingerprintSnapshotAlignmentService(
                new ActiveOnlyStore(Optional.of(snapshot(projectId, "index-1"))));

        IOException refused = assertThrows(IOException.class,
                () -> service.loadAlignedWithActiveIndex(ProjectIndexState.neverIndexed(projectId, OBSERVED)));
        assertTrue(refused.getMessage().contains("without an active index snapshot"), refused.getMessage());
    }

    @Test
    void neverIndexedWithoutAFingerprintHasNothingToAlign() throws Exception {
        ProjectFingerprintSnapshotAlignmentService service =
                new ProjectFingerprintSnapshotAlignmentService(new ActiveOnlyStore(Optional.empty()));

        assertTrue(service.loadAlignedWithActiveIndex(
                ProjectIndexState.neverIndexed(UUID.randomUUID(), OBSERVED)).isEmpty());
    }

    @Test
    void theStateIsRequired() {
        ProjectFingerprintSnapshotAlignmentService service =
                new ProjectFingerprintSnapshotAlignmentService(new ActiveOnlyStore(Optional.empty()));

        assertThrows(NullPointerException.class, () -> service.loadAlignedWithActiveIndex(null));
        assertThrows(NullPointerException.class, () -> new ProjectFingerprintSnapshotAlignmentService(null));
    }

    private ProjectFingerprintSnapshot snapshot(UUID projectId, String indexSnapshotId) throws IOException {
        Path project = Files.createDirectories(temporary.resolve("project-" + indexSnapshotId));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        ProjectFingerprint fingerprint = new ProjectFingerprintService().capture(project);
        return new ProjectFingerprintSnapshot(projectId, indexSnapshotId, fingerprint);
    }

    private static ProjectIndexState ready(UUID projectId, String snapshotId) {
        return new ProjectIndexState(projectId, ProjectIndexState.Availability.READY, Optional.of(snapshotId),
                Optional.empty(), OBSERVED, Optional.of("ready"));
    }

    /** Only {@code loadActive} matters to the alignment rule; the rest of the store is not used. */
    private record ActiveOnlyStore(Optional<ProjectFingerprintSnapshot> active) implements ProjectFingerprintSnapshotStore {
        @Override
        public ProjectFingerprintSnapshot publish(UUID projectId, String indexSnapshotId, ProjectFingerprint fingerprint) {
            throw new UnsupportedOperationException("not used by the alignment rule");
        }

        @Override
        public void promote(UUID projectId, String indexSnapshotId) {
            throw new UnsupportedOperationException("not used by the alignment rule");
        }

        @Override
        public Optional<ProjectFingerprintSnapshot> load(UUID projectId, String indexSnapshotId) {
            return Optional.empty();
        }

        @Override
        public Optional<ProjectFingerprintSnapshot> loadActive(UUID projectId) {
            return active;
        }

        @Override
        public List<String> listIndexSnapshotIds(UUID projectId) {
            return List.of();
        }
    }
}
