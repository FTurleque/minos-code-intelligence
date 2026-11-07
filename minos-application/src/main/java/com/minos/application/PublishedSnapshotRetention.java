package com.minos.application;

import com.minos.storage.StorageRetentionService;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage retention applied once a snapshot is published, by an indexing or an explicit SCIP import.
 *
 * <p>It is maintenance after a publication that already succeeded. Its lock is bounded, so it can time out behind a
 * concurrent compaction: that must not turn the publication into a failure. An input/output failure becomes a fixed
 * diagnostic that never carries the cause, which may name a path; an unchecked failure is a defect and propagates.</p>
 */
public final class PublishedSnapshotRetention {

    /** The diagnostic of a retention that did not run; the next indexing or import applies it. */
    public static final String SKIPPED_DIAGNOSTIC =
            "storage retention did not run (its lock was busy or unavailable); it runs after the next indexing";

    private final StorageRetentionService retentionService;

    public PublishedSnapshotRetention(StorageRetentionService retentionService) {
        this.retentionService = Objects.requireNonNull(retentionService, "retentionService");
    }

    /** Compacts the project under the default policy; the diagnostic is present only when the retention did not run. */
    public Optional<String> compact(UUID projectId) {
        try {
            retentionService.compact(projectId);
            return Optional.empty();
        } catch (IOException ignored) {
            return Optional.of(SKIPPED_DIAGNOSTIC);
        }
    }
}
