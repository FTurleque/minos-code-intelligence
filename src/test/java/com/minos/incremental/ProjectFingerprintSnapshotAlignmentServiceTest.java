package com.minos.incremental;

import com.minos.orchestration.ProjectIndexState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectFingerprintSnapshotAlignmentServiceTest {

    @Test
    void returnsEmptyWhenAnActiveIndexHasNoFingerprintBaselineYet(@TempDir Path root) throws Exception {
        UUID projectId = UUID.randomUUID();
        FileProjectFingerprintSnapshotStore store = new FileProjectFingerprintSnapshotStore(root.resolve("storage"));
        ProjectFingerprintSnapshotAlignmentService service = new ProjectFingerprintSnapshotAlignmentService(store);

        ProjectIndexState state = readyState(projectId, "index-1");
        assertTrue(service.loadAlignedWithActiveIndex(state).isEmpty());
    }

    @Test
    void returnsFingerprintOnlyWhenItsIndexSnapshotMatchesTheActiveIndex(@TempDir Path root) throws Exception {
        Path project = root.resolve("project");
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.writeString(project.resolve("src/App.java"), "class App {}");
        UUID projectId = UUID.randomUUID();
        FileProjectFingerprintSnapshotStore store = new FileProjectFingerprintSnapshotStore(root.resolve("storage"));
        ProjectFingerprint fingerprint = new ProjectFingerprintService().capture(project);
        store.publish(projectId, "index-1", fingerprint);
        store.promote(projectId, "index-1");
        ProjectFingerprintSnapshotAlignmentService service = new ProjectFingerprintSnapshotAlignmentService(store);

        ProjectFingerprintSnapshot aligned = service.loadAlignedWithActiveIndex(readyState(projectId, "index-1"))
                .orElseThrow();
        assertEquals("index-1", aligned.indexSnapshotId());
        assertEquals(fingerprint, aligned.fingerprint());

        IOException mismatch = assertThrows(
                IOException.class,
                () -> service.loadAlignedWithActiveIndex(readyState(projectId, "index-2"))
        );
        assertTrue(mismatch.getMessage().contains("not aligned"));
    }

    @Test
    void rejectsAnActiveFingerprintWhenTheLifecycleHasNoActiveIndex(@TempDir Path root) throws Exception {
        Path project = root.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        UUID projectId = UUID.randomUUID();
        FileProjectFingerprintSnapshotStore store = new FileProjectFingerprintSnapshotStore(root.resolve("storage"));
        store.publish(projectId, "index-1", new ProjectFingerprintService().capture(project));
        store.promote(projectId, "index-1");
        ProjectFingerprintSnapshotAlignmentService service = new ProjectFingerprintSnapshotAlignmentService(store);

        ProjectIndexState neverIndexed = ProjectIndexState.neverIndexed(projectId, Instant.parse("2026-07-23T00:00:00Z"));
        IOException mismatch = assertThrows(
                IOException.class,
                () -> service.loadAlignedWithActiveIndex(neverIndexed)
        );
        assertTrue(mismatch.getMessage().contains("without an active index snapshot"));
    }

    private static ProjectIndexState readyState(UUID projectId, String snapshotId) {
        return new ProjectIndexState(
                projectId,
                ProjectIndexState.Availability.READY,
                Optional.of(snapshotId),
                Optional.empty(),
                Instant.parse("2026-07-23T00:00:00Z"),
                Optional.of("ready")
        );
    }
}
