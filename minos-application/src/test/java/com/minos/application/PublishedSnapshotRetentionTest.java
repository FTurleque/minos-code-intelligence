package com.minos.application;

import com.minos.storage.StorageRetentionService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The retention applied after a published snapshot: maintenance that never turns the publication into a failure. */
class PublishedSnapshotRetentionTest {

    private static final String SKIPPED =
            "storage retention did not run (its lock was busy or unavailable); it runs after the next indexing";

    @Test
    void anAppliedRetentionLeavesNoDiagnosticAndCompactsTheProject() {
        UUID project = UUID.randomUUID();
        AtomicReference<UUID> compacted = new AtomicReference<>();
        PublishedSnapshotRetention retention = new PublishedSnapshotRetention((projectId, policy) -> {
            compacted.set(projectId);
            return new StorageRetentionService.RetentionResult(1, 0, 0);
        });

        assertEquals(Optional.empty(), retention.compact(project));
        assertEquals(project, compacted.get());
    }

    @Test
    void anInputOutputFailureIsAFixedDiagnosticWithoutItsCause() {
        String absolute = System.getProperty("java.io.tmpdir");
        PublishedSnapshotRetention retention = new PublishedSnapshotRetention((projectId, policy) -> {
            throw new IOException("timed out waiting for storage retention lock at " + absolute);
        });

        Optional<String> diagnostic = retention.compact(UUID.randomUUID());

        assertEquals(Optional.of(SKIPPED), diagnostic);
        assertEquals(SKIPPED, PublishedSnapshotRetention.SKIPPED_DIAGNOSTIC);
        assertFalse(diagnostic.orElseThrow().contains(absolute), "the cause may name a path");
    }

    @Test
    void anUncheckedFailureIsNotSwallowed() {
        IllegalStateException defect = new IllegalStateException("defect");
        PublishedSnapshotRetention retention = new PublishedSnapshotRetention((projectId, policy) -> {
            throw defect;
        });

        UUID project = UUID.randomUUID();
        assertSame(defect, assertThrows(IllegalStateException.class, () -> retention.compact(project)));
    }

    @Test
    void theRetentionServiceIsRequired() {
        assertThrows(NullPointerException.class, () -> new PublishedSnapshotRetention(null));
    }
}
