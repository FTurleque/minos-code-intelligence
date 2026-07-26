package com.minos.architecture;

import com.minos.domain.Evidence;
import com.minos.domain.InformationNature;

import java.util.List;
import java.util.Objects;

/**
 * Vue compacte des dépendances persistées agrégées au niveau module.
 */
public record ArchitectureDependencyGraph(
        String projectId,
        String snapshotId,
        int totalDependencyCount,
        int interModuleDependencyCount,
        int intraModuleDependencyCount,
        int unassignedDependencyCount,
        List<ArchitectureModuleDependency> dependencies,
        InformationNature nature,
        List<Evidence> evidence
) {
    public ArchitectureDependencyGraph {
        requireText(projectId, "projectId");
        requireText(snapshotId, "snapshotId");
        requireNonNegative(totalDependencyCount, "totalDependencyCount");
        requireNonNegative(interModuleDependencyCount, "interModuleDependencyCount");
        requireNonNegative(intraModuleDependencyCount, "intraModuleDependencyCount");
        requireNonNegative(unassignedDependencyCount, "unassignedDependencyCount");
        if (interModuleDependencyCount + intraModuleDependencyCount + unassignedDependencyCount
                != totalDependencyCount) {
            throw new IllegalArgumentException("dependency counters must add up to totalDependencyCount");
        }
        dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        nature = Objects.requireNonNull(nature, "nature");
        if (nature == InformationNature.FACTUAL) {
            throw new IllegalArgumentException("architecture dependency graph must be derived");
        }
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.isEmpty()) {
            throw new IllegalArgumentException("architecture dependency graph requires evidence");
        }
    }

    public int moduleEdgeCount() {
        return dependencies.size();
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    private static void requireNonNegative(int value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + " must not be negative");
        }
    }
}
