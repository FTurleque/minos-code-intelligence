package com.minos.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Explicit snapshot-retention mechanism. M15 separates the mechanism from storage;
 * the automatic retention policy remains a later measured decision.
 */
public final class SnapshotRetentionService {

    private final SnapshotRepository repository;

    public SnapshotRetentionService(SnapshotRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public List<String> listSnapshotFiles(UUID projectId) throws IOException {
        return repository.listSnapshotFiles(projectId).stream()
                .map(path -> path.getFileName().toString())
                .toList();
    }

    /**
     * Deletes only explicitly named historical snapshots and refuses to remove the active file.
     */
    public int deleteHistoricalSnapshots(
            UUID projectId,
            Collection<String> fileNames,
            String activeFileName
    ) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(fileNames, "fileNames");
        Set<String> requested = fileNames.stream()
                .map(name -> Objects.requireNonNull(name, "fileNames must not contain null"))
                .collect(Collectors.toUnmodifiableSet());
        if (activeFileName != null && requested.contains(activeFileName)) {
            throw new IllegalArgumentException("active snapshot must not be deleted by retention");
        }

        int deleted = 0;
        for (String fileName : requested) {
            Path file = repository.resolveSnapshotFile(projectId, fileName);
            if (Files.deleteIfExists(file)) {
                deleted++;
            }
        }
        return deleted;
    }
}
