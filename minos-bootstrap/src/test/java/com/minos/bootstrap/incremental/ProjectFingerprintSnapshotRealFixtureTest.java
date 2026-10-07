package com.minos.bootstrap.incremental;

import com.minos.incremental.ProjectFingerprint;
import com.minos.incremental.ProjectFingerprintService;
import com.minos.incremental.ProjectFingerprintSnapshot;
import com.minos.incremental.ProjectFingerprintSnapshotAlignmentService;
import com.minos.orchestration.ProjectIndexState;
import com.minos.storage.local.incremental.FileProjectFingerprintSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectFingerprintSnapshotRealFixtureTest {

    @Test
    void persistsAndReopensFingerprintForVersionedTypescriptFixture(@TempDir Path storage) throws Exception {
        Path fixture = Path.of("fixtures/typescript/typescript-modules");
        ProjectFingerprint fingerprint = new ProjectFingerprintService().capture(fixture);
        UUID projectId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        String indexSnapshotId = "typescript-modules-index";

        FileProjectFingerprintSnapshotStore writer = new FileProjectFingerprintSnapshotStore(storage);
        writer.publish(projectId, indexSnapshotId, fingerprint);
        writer.promote(projectId, indexSnapshotId);

        FileProjectFingerprintSnapshotStore reader = new FileProjectFingerprintSnapshotStore(storage);
        ProjectIndexState indexState = new ProjectIndexState(
                projectId,
                ProjectIndexState.Availability.READY,
                Optional.of(indexSnapshotId),
                Optional.empty(),
                Instant.parse("2026-07-23T00:00:00Z"),
                Optional.of("fixture index active")
        );
        ProjectFingerprintSnapshot reopened = new ProjectFingerprintSnapshotAlignmentService(reader)
                .loadAlignedWithActiveIndex(indexState)
                .orElseThrow();

        assertEquals(indexSnapshotId, reopened.indexSnapshotId());
        assertEquals(fingerprint, reopened.fingerprint());
        assertEquals(13, reopened.fingerprint().fileCount());
        assertEquals(1, reader.listIndexSnapshotIds(projectId).size());

        System.out.printf(
                "M7.2 typescript-modules fingerprint-snapshot: index=%s, files=%d, history=%d, project=%s, build=%s%n",
                reopened.indexSnapshotId(),
                reopened.fingerprint().fileCount(),
                reader.listIndexSnapshotIds(projectId).size(),
                reopened.fingerprint().projectSha256(),
                reopened.fingerprint().buildSha256()
        );
    }
}
