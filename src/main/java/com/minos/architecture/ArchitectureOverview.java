package com.minos.architecture;

import com.minos.domain.Evidence;
import com.minos.domain.InformationNature;

import java.util.List;
import java.util.Objects;

/**
 * Vue compacte de topologie d'un projet MINOS.
 */
public record ArchitectureOverview(
        String projectId,
        String projectName,
        String snapshotId,
        List<String> languages,
        List<String> buildSystems,
        int localSymbolCount,
        int externalSymbolCount,
        int relationshipCount,
        int unassignedLocalSymbolCount,
        List<ArchitectureModule> modules,
        InformationNature nature,
        List<Evidence> evidence
) {

    public ArchitectureOverview {
        requireText(projectId, "projectId");
        requireText(projectName, "projectName");
        requireText(snapshotId, "snapshotId");
        languages = List.copyOf(Objects.requireNonNull(languages, "languages"));
        buildSystems = List.copyOf(Objects.requireNonNull(buildSystems, "buildSystems"));
        requireNonNegative(localSymbolCount, "localSymbolCount");
        requireNonNegative(externalSymbolCount, "externalSymbolCount");
        requireNonNegative(relationshipCount, "relationshipCount");
        requireNonNegative(unassignedLocalSymbolCount, "unassignedLocalSymbolCount");
        if (unassignedLocalSymbolCount > localSymbolCount) {
            throw new IllegalArgumentException("unassignedLocalSymbolCount exceeds localSymbolCount");
        }
        modules = List.copyOf(Objects.requireNonNull(modules, "modules"));
        nature = Objects.requireNonNull(nature, "nature");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (nature != InformationNature.FACTUAL && evidence.isEmpty()) {
            throw new IllegalArgumentException("derived architecture overview requires evidence");
        }
    }

    public int moduleCount() {
        return modules.size();
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
