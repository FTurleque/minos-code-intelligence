package com.minos.incremental;

import com.minos.source.SourceBudgetPolicy;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Snapshot déterministe des empreintes visibles d'un projet.
 */
public record ProjectFingerprint(
        String projectSha256,
        String buildSha256,
        List<FileFingerprint> files
) {
    public ProjectFingerprint {
        projectSha256 = FileFingerprint.requireSha256(projectSha256);
        buildSha256 = FileFingerprint.requireSha256(buildSha256);
        Objects.requireNonNull(files, "files");
        if (files.size() > SourceBudgetPolicy.DEFAULT_MAX_FILES) {
            throw new IllegalArgumentException(
                    "files exceeds source budget: " + files.size() + "/" + SourceBudgetPolicy.DEFAULT_MAX_FILES);
        }
        files = List.copyOf(files);

        List<FileFingerprint> sorted = files.stream()
                .sorted(Comparator.comparing(FileFingerprint::relativePath))
                .toList();
        if (!files.equals(sorted)) {
            throw new IllegalArgumentException("files must be sorted by relativePath");
        }

        Set<String> paths = new HashSet<>();
        for (FileFingerprint file : files) {
            if (!paths.add(file.relativePath())) {
                throw new IllegalArgumentException("duplicate file fingerprint: " + file.relativePath());
            }
        }
    }

    public int fileCount() {
        return files.size();
    }
}
