package com.minos.incremental;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Évaluation fournisseur-indépendante de l'étendue d'invalidation d'un projet.
 *
 * <p>{@link ProjectInvalidationScope#PARTIAL_CANDIDATE} signifie uniquement que
 * les changements observés sont bornés à des sources/tests reconnus. Une capacité
 * fournisseur devra encore être prouvée avant toute exécution partielle.</p>
 */
public record ProjectInvalidationAssessment(
        UUID projectId,
        Optional<String> activeIndexSnapshotId,
        Optional<String> baselineIndexSnapshotId,
        ProjectInvalidationScope scope,
        List<ProjectInvalidationReason> reasons,
        Optional<ProjectChangeSet> changeSet,
        List<String> changedSourceFiles,
        List<String> changedTestFiles,
        List<String> unqualifiedChangedFiles
) {
    public ProjectInvalidationAssessment {
        Objects.requireNonNull(projectId, "projectId");
        activeIndexSnapshotId = immutableText(activeIndexSnapshotId, "activeIndexSnapshotId");
        baselineIndexSnapshotId = immutableText(baselineIndexSnapshotId, "baselineIndexSnapshotId");
        Objects.requireNonNull(scope, "scope");
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        changeSet = Objects.requireNonNull(changeSet, "changeSet");
        changedSourceFiles = immutableSortedPaths(changedSourceFiles, "changedSourceFiles");
        changedTestFiles = immutableSortedPaths(changedTestFiles, "changedTestFiles");
        unqualifiedChangedFiles = immutableSortedPaths(unqualifiedChangedFiles, "unqualifiedChangedFiles");

        for (int index = 1; index < reasons.size(); index++) {
            if (reasons.get(index - 1).compareTo(reasons.get(index)) >= 0) {
                throw new IllegalArgumentException("reasons must be strictly sorted and unique");
            }
        }

        switch (scope) {
            case NONE -> {
                if (changeSet.isEmpty() || changeSet.orElseThrow().projectChanged()) {
                    throw new IllegalArgumentException("NONE requires an unchanged ProjectChangeSet");
                }
                if (!reasons.isEmpty() || !changedSourceFiles.isEmpty()
                        || !changedTestFiles.isEmpty() || !unqualifiedChangedFiles.isEmpty()) {
                    throw new IllegalArgumentException("NONE must not expose invalidation reasons or changed files");
                }
            }
            case PARTIAL_CANDIDATE -> {
                if (changeSet.isEmpty() || !changeSet.orElseThrow().projectChanged()) {
                    throw new IllegalArgumentException("PARTIAL_CANDIDATE requires a changed ProjectChangeSet");
                }
                if (changedSourceFiles.isEmpty() && changedTestFiles.isEmpty()) {
                    throw new IllegalArgumentException("PARTIAL_CANDIDATE requires source or test changes");
                }
                if (!unqualifiedChangedFiles.isEmpty()) {
                    throw new IllegalArgumentException("PARTIAL_CANDIDATE cannot contain unqualified changes");
                }
                if (!reasons.equals(List.of(ProjectInvalidationReason.SOURCE_OR_TEST_CHANGED))) {
                    throw new IllegalArgumentException("PARTIAL_CANDIDATE requires only SOURCE_OR_TEST_CHANGED");
                }
            }
            case FULL_REQUIRED -> {
                if (reasons.isEmpty()) {
                    throw new IllegalArgumentException("FULL_REQUIRED requires at least one reason");
                }
            }
        }
    }

    private static Optional<String> immutableText(Optional<String> value, String label) {
        return Objects.requireNonNull(value, label).map(text -> {
            if (text.isBlank()) {
                throw new IllegalArgumentException(label + " must not contain blank text");
            }
            return text;
        });
    }

    private static List<String> immutableSortedPaths(List<String> paths, String label) {
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
}
