package com.minos.architecture;

import com.minos.domain.Evidence;
import com.minos.domain.InformationNature;

import java.util.List;
import java.util.Objects;

/**
 * Agrégat explicable d'un ensemble de dépendances symbole→symbole entre deux
 * modules découverts.
 */
public record ArchitectureModuleDependency(
        String id,
        String sourceModuleId,
        String targetModuleId,
        int dependencyCount,
        int sourceSymbolCount,
        int targetSymbolCount,
        List<String> sampleDependencyIds,
        InformationNature nature,
        Double confidence,
        List<Evidence> evidence
) {
    public ArchitectureModuleDependency {
        requireText(id, "id");
        requireText(sourceModuleId, "sourceModuleId");
        requireText(targetModuleId, "targetModuleId");
        if (sourceModuleId.equals(targetModuleId)) {
            throw new IllegalArgumentException("module dependency must be inter-module");
        }
        requirePositive(dependencyCount, "dependencyCount");
        requirePositive(sourceSymbolCount, "sourceSymbolCount");
        requirePositive(targetSymbolCount, "targetSymbolCount");
        sampleDependencyIds = List.copyOf(Objects.requireNonNull(sampleDependencyIds, "sampleDependencyIds"));
        if (sampleDependencyIds.isEmpty()) {
            throw new IllegalArgumentException("sampleDependencyIds must not be empty");
        }
        nature = Objects.requireNonNull(nature, "nature");
        if (nature == InformationNature.FACTUAL) {
            throw new IllegalArgumentException("module dependency aggregate must be derived");
        }
        if (confidence == null || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.isEmpty()) {
            throw new IllegalArgumentException("module dependency aggregate requires evidence");
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    private static void requirePositive(int value, String fieldName) {
        if (value < 1) {
            throw new IllegalArgumentException(fieldName + " must be greater than zero");
        }
    }
}
