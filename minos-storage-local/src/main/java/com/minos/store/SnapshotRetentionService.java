package com.minos.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Snapshot-retention mechanism separated from persistence.
 *
 * <p>M16 adds a measured count-based policy while preserving the active snapshot unconditionally.</p>
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

    /**
     * Applies deterministic count-based retention to historical snapshots.
     *
     * <p>The active file is never counted against the historical allowance and is never deleted.
     * Historical snapshots are ordered newest-first using last-modified time then file name for a
     * stable tie-breaker.</p>
     */
    public RetentionResult applyPolicy(
            UUID projectId,
            String activeFileName,
            SnapshotRetentionPolicy policy
    ) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(policy, "policy");
        if (activeFileName == null || activeFileName.isBlank()) {
            throw new IllegalArgumentException("activeFileName must not be blank");
        }

        List<Path> files = new ArrayList<>(repository.listSnapshotFiles(projectId));
        Path active = repository.resolveSnapshotFile(projectId, activeFileName);
        if (!Files.isRegularFile(active)) {
            throw new IOException("active snapshot file is missing: " + active);
        }

        files.removeIf(path -> path.getFileName().toString().equals(activeFileName));
        files.sort(Comparator
                .comparing(SnapshotRetentionService::lastModifiedSafe)
                .reversed()
                .thenComparing(path -> path.getFileName().toString()));

        int keep = Math.min(policy.maxHistoricalSnapshots(), files.size());
        List<String> retained = files.subList(0, keep).stream()
                .map(path -> path.getFileName().toString())
                .toList();
        List<String> deleted = new ArrayList<>();
        for (Path path : files.subList(keep, files.size())) {
            if (Files.deleteIfExists(path)) {
                deleted.add(path.getFileName().toString());
            }
        }
        return new RetentionResult(activeFileName, retained, List.copyOf(deleted));
    }

    private static FileTime lastModifiedSafe(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException("cannot read snapshot timestamp: " + path, exception);
        }
    }

    public record RetentionResult(
            String activeFileName,
            List<String> retainedHistoricalFiles,
            List<String> deletedHistoricalFiles
    ) {
        public RetentionResult {
            if (activeFileName == null || activeFileName.isBlank()) {
                throw new IllegalArgumentException("activeFileName must not be blank");
            }
            retainedHistoricalFiles = List.copyOf(Objects.requireNonNull(
                    retainedHistoricalFiles, "retainedHistoricalFiles"));
            deletedHistoricalFiles = List.copyOf(Objects.requireNonNull(
                    deletedHistoricalFiles, "deletedHistoricalFiles"));
        }
    }
}
