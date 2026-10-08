package com.minos.storage.local.store;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Snapshot-retention mechanism separated from persistence. */
public final class SnapshotRetentionService {

    /** Aligned on the resume TTL (ADR 0039): older prepared-snapshot temporaries are orphans. */
    public static final Duration DEFAULT_ORPHAN_MAX_AGE = Duration.ofHours(24);
    private static final String PREPARED_SNAPSHOT_PREFIX = ".snapshot-";
    private static final String POINTER_TEMPORARY_PREFIX = ".active-";
    private static final String TEMPORARY_SUFFIX = ".tmp";

    private final SnapshotRepository repository;

    public SnapshotRetentionService(SnapshotRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public List<String> listSnapshotFiles(UUID projectId) throws IOException {
        return repository.listSnapshotFiles(projectId).stream()
                .map(path -> path.getFileName().toString())
                .toList();
    }

    /** Deletes only explicitly named historical snapshots and refuses to remove the active file. */
    public int deleteHistoricalSnapshots(
            UUID projectId,
            Collection<String> fileNames,
            String activeFileName
    ) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        try (SnapshotProjectLease ignored = SnapshotProjectLease.acquire(repository.storageRoot(), projectId)) {
            return deleteHistoricalSnapshotsLocked(projectId, fileNames, activeFileName);
        }
    }

    private int deleteHistoricalSnapshotsLocked(
            UUID projectId,
            Collection<String> fileNames,
            String activeFileName
    ) throws IOException {
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

    /** Applies deterministic count-based retention to historical snapshots. */
    public RetentionResult applyPolicy(
            UUID projectId,
            String activeFileName,
            SnapshotRetentionPolicy policy
    ) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        try (SnapshotProjectLease ignored = SnapshotProjectLease.acquire(repository.storageRoot(), projectId)) {
            deleteOrphanPreparedSnapshotsLocked(projectId, Instant.now(), DEFAULT_ORPHAN_MAX_AGE);
            return applyPolicyLocked(projectId, activeFileName, policy);
        }
    }

    /**
     * Reclaims prepared-snapshot temporaries ({@code .snapshot-*.tmp}) older than {@code maxAge}
     * (ADR 0039 §4). A prepared snapshot is written to such a temporary and then atomically
     * published under its final name, so a temporary is never referenced by any run
     * (RUNNING or INTERRUPTED runs reference published staged snapshots only): an old temporary can
     * only be the residue of a staging interrupted mid-write. Recent temporaries are left alone
     * because a staging in progress writes its temporary before it takes the project lease; the
     * age check and the deletion happen under that lease, so a publication cannot slip between them.
     * Active-pointer temporaries ({@code .active-*.tmp}) older than {@code maxAge} are reclaimed by
     * the same rule (MINOS-AUD-H13): a promotion writes its pointer temporary and renames it over the
     * active pointer within one lease, so an old one is the residue of a promotion killed mid-write.
     */
    public int deleteOrphanPreparedSnapshots(UUID projectId, Instant now, Duration maxAge) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        try (SnapshotProjectLease ignored = SnapshotProjectLease.acquire(repository.storageRoot(), projectId)) {
            return deleteOrphanPreparedSnapshotsLocked(projectId, now, maxAge);
        }
    }

    private int deleteOrphanPreparedSnapshotsLocked(UUID projectId, Instant now, Duration maxAge) throws IOException {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(maxAge, "maxAge");
        if (maxAge.isZero() || maxAge.isNegative()) throw new IllegalArgumentException("maxAge must be positive");
        Path directory = repository.projectDirectory(projectId);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return 0;
        Instant cutoff = now.minus(maxAge);
        int deleted = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                boolean temporary = name.startsWith(PREPARED_SNAPSHOT_PREFIX) || name.startsWith(POINTER_TEMPORARY_PREFIX);
                if (!temporary || !name.endsWith(TEMPORARY_SUFFIX)) continue;
                if (Files.isSymbolicLink(entry) || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)) continue;
                if (Files.deleteIfExists(entry)) deleted++;
            }
        }
        return deleted;
    }

    RetentionResult applyPolicyLocked(
            UUID projectId,
            String activeFileName,
            SnapshotRetentionPolicy policy
    ) throws IOException {
        return applyPolicyLocked(projectId, activeFileName, policy, Set.of());
    }

    RetentionResult applyPolicyLocked(
            UUID projectId,
            String activeFileName,
            SnapshotRetentionPolicy policy,
            Set<String> protectedFileNamePrefixes
    ) throws IOException {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(protectedFileNamePrefixes, "protectedFileNamePrefixes");
        if (activeFileName == null || activeFileName.isBlank()) {
            throw new IllegalArgumentException("activeFileName must not be blank");
        }

        List<Path> files = new ArrayList<>(repository.listSnapshotFiles(projectId));
        Path active = repository.resolveSnapshotFile(projectId, activeFileName);
        if (!Files.isRegularFile(active)) {
            throw new IOException("active snapshot file is missing: " + active);
        }

        files.removeIf(path -> path.getFileName().toString().equals(activeFileName));
        List<Path> protectedHistorical = files.stream()
                .filter(path -> protectedFileNamePrefixes.stream()
                        .anyMatch(path.getFileName().toString()::startsWith))
                .toList();
        files.removeAll(protectedHistorical);
        files.sort(Comparator
                .comparing(SnapshotRetentionService::lastModifiedSafe)
                .reversed()
                .thenComparing(path -> path.getFileName().toString()));

        int keep = Math.min(policy.maxHistoricalSnapshots(), files.size());
        List<String> retained = new ArrayList<>();
        files.subList(0, keep).stream()
                .map(path -> path.getFileName().toString())
                .forEach(retained::add);
        protectedHistorical.stream()
                .map(path -> path.getFileName().toString())
                .sorted()
                .forEach(retained::add);
        List<String> deleted = new ArrayList<>();
        for (Path path : files.subList(keep, files.size())) {
            if (Files.deleteIfExists(path)) {
                deleted.add(path.getFileName().toString());
            }
        }
        return new RetentionResult(activeFileName, List.copyOf(retained), List.copyOf(deleted));
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
