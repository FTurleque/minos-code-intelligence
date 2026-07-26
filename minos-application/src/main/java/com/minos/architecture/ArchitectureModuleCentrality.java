package com.minos.architecture;

import com.minos.domain.Evidence;
import com.minos.domain.InformationNature;

import java.util.List;
import java.util.Objects;

/**
 * Classement relatif et directionnel d'un module dans le graphe d'architecture.
 *
 * <p>Le rang 0 signifie qu'aucun signal de dépendance n'existe dans la direction
 * considérée. Les rangs positifs sont denses et commencent à 1.</p>
 */
public record ArchitectureModuleCentrality(
        String moduleId,
        int incomingRank,
        int outgoingRank,
        int incomingDependencyCount,
        int outgoingDependencyCount,
        int incomingModuleCount,
        int outgoingModuleCount,
        double incomingShare,
        double outgoingShare,
        InformationNature nature,
        List<Evidence> evidence
) {
    public ArchitectureModuleCentrality {
        requireText(moduleId, "moduleId");
        requireNonNegative(incomingRank, "incomingRank");
        requireNonNegative(outgoingRank, "outgoingRank");
        requireNonNegative(incomingDependencyCount, "incomingDependencyCount");
        requireNonNegative(outgoingDependencyCount, "outgoingDependencyCount");
        requireNonNegative(incomingModuleCount, "incomingModuleCount");
        requireNonNegative(outgoingModuleCount, "outgoingModuleCount");
        requireShare(incomingShare, "incomingShare");
        requireShare(outgoingShare, "outgoingShare");
        if ((incomingDependencyCount == 0) != (incomingRank == 0)) {
            throw new IllegalArgumentException("incomingRank must be 0 exactly when incomingDependencyCount is 0");
        }
        if ((outgoingDependencyCount == 0) != (outgoingRank == 0)) {
            throw new IllegalArgumentException("outgoingRank must be 0 exactly when outgoingDependencyCount is 0");
        }
        nature = Objects.requireNonNull(nature, "nature");
        if (nature == InformationNature.FACTUAL) {
            throw new IllegalArgumentException("module centrality must be derived");
        }
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.isEmpty()) {
            throw new IllegalArgumentException("module centrality requires evidence");
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
