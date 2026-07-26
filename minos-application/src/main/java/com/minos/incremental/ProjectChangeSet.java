package com.minos.incremental;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Diff factuel entre deux snapshots d'empreintes projet.
 *
 * <p>Ce contrat décrit ce qui a changé. Il ne décide pas si une indexation
 * partielle est sûre.</p>
 */
public record ProjectChangeSet(
        String previousProjectSha256,
        String currentProjectSha256,
        String previousBuildSha256,
        String currentBuildSha256,
        boolean projectChanged,
        boolean buildDefinitionChanged,
        List<String> addedFiles,
        List<String> modifiedFiles,
        List<String> deletedFiles,
        List<String> unchangedFiles
) {
    public ProjectChangeSet {
        previousProjectSha256 = FileFingerprint.requireSha256(previousProjectSha256);
        currentProjectSha256 = FileFingerprint.requireSha256(currentProjectSha256);
        previousBuildSha256 = FileFingerprint.requireSha256(previousBuildSha256);
        currentBuildSha256 = FileFingerprint.requireSha256(currentBuildSha256);
        addedFiles = immutablePaths(addedFiles, "addedFiles");
        modifiedFiles = immutablePaths(modifiedFiles, "modifiedFiles");
        deletedFiles = immutablePaths(deletedFiles, "deletedFiles");
        unchangedFiles = immutablePaths(unchangedFiles, "unchangedFiles");
        requireDisjoint(addedFiles, modifiedFiles, deletedFiles, unchangedFiles);

        boolean hasFileChanges = !addedFiles.isEmpty() || !modifiedFiles.isEmpty() || !deletedFiles.isEmpty();
        if (projectChanged != hasFileChanges) {
            throw new IllegalArgumentException("projectChanged must match added/modified/deleted files");
        }
        if (projectChanged != !previousProjectSha256.equals(currentProjectSha256)) {
            throw new IllegalArgumentException("projectChanged must match project fingerprints");
        }
        if (buildDefinitionChanged != !previousBuildSha256.equals(currentBuildSha256)) {
            throw new IllegalArgumentException("buildDefinitionChanged must match build fingerprints");
        }
    }

    public int changedFileCount() {
        return addedFiles.size() + modifiedFiles.size() + deletedFiles.size();
    }

    private static List<String> immutablePaths(List<String> paths, String label) {
        List<String> copy = List.copyOf(Objects.requireNonNull(paths, label));
        String previous = null;
        for (String path : copy) {
            Objects.requireNonNull(path, label + " element");
            if (path.isBlank() || path.startsWith("/") || path.contains("\\")) {
                throw new IllegalArgumentException(label + " must contain portable relative paths");
            }
            if (previous != null && previous.compareTo(path) >= 0) {
                throw new IllegalArgumentException(label + " must be strictly sorted and unique");
            }
            previous = path;
        }
        return copy;
    }

    @SafeVarargs
    private static void requireDisjoint(List<String>... groups) {
        Set<String> paths = new HashSet<>();
        for (List<String> group : groups) {
            for (String path : group) {
                if (!paths.add(path)) {
                    throw new IllegalArgumentException("a file path cannot belong to multiple change categories: " + path);
                }
            }
        }
    }
}
