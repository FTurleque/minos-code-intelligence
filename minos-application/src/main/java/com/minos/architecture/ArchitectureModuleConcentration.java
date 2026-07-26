package com.minos.architecture;

import com.minos.domain.Evidence;
import com.minos.domain.InformationNature;

import java.util.List;
import java.util.Objects;

/**
 * Mesures descriptives de concentration pour un module, sans classification
 * sémantique de centralité ou de criticité.
 */
public record ArchitectureModuleConcentration(
        String moduleId,
        int incomingDependencyCount,
        int outgoingDependencyCount,
        int incomingModuleCount,
        int outgoingModuleCount,
        double incomingShare,
        double outgoingShare,
        InformationNature nature,
        List<Evidence> evidence
) {
    public ArchitectureModuleConcentration {
        requireText(moduleId, "moduleId");
        requireNonNegative(incomingDependencyCount, "incomingDependencyCount");
        requireNonNegative(outgoingDependencyCount, "outgoingDependencyCount");
        requireNonNegative(incomingModuleCount, "incomingModuleCount");
        requireNonNegative(outgoingModuleCount, "outgoingModuleCount");
        requireShare(incomingShare, "incomingShare");
        requireShare(outgoingShare, "outgoingShare");
        nature = Objects.requireNonNull(nature, "nature");
        if (nature == InformationNature.FACTUAL) {
            throw new IllegalArgumentException("module concentration must be derived");
        }
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.isEmpty()) {
            throw new IllegalArgumentException("module concentration requires evidence");
        }
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

    private static void requireShare(double value, String fieldName) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(fieldName + " must be between 0 and 1");
        }
    }
}
