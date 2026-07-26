package com.minos.architecture;

import com.minos.domain.Evidence;
import com.minos.domain.InformationNature;

import java.util.List;
import java.util.Objects;

/**
 * Rapport de concentration descriptif du graphe inter-module.
 *
 * <p>Aucun seuil de centralité n'est appliqué : les valeurs restent des mesures
 * dérivées que les incréments suivants pourront qualifier expérimentalement.</p>
 */
public record ArchitectureConcentrationReport(
        String projectId,
        String snapshotId,
        int moduleCount,
        int interModuleDependencyCount,
        double incomingHerfindahlIndex,
        double outgoingHerfindahlIndex,
        double maxIncomingShare,
        double maxOutgoingShare,
        List<ArchitectureModuleConcentration> modules,
        InformationNature nature,
        List<Evidence> evidence
) {
    public ArchitectureConcentrationReport {
        requireText(projectId, "projectId");
        requireText(snapshotId, "snapshotId");
        requireNonNegative(moduleCount, "moduleCount");
        requireNonNegative(interModuleDependencyCount, "interModuleDependencyCount");
        requireShare(incomingHerfindahlIndex, "incomingHerfindahlIndex");
        requireShare(outgoingHerfindahlIndex, "outgoingHerfindahlIndex");
        requireShare(maxIncomingShare, "maxIncomingShare");
        requireShare(maxOutgoingShare, "maxOutgoingShare");
        modules = List.copyOf(Objects.requireNonNull(modules, "modules"));
        if (modules.size() != moduleCount) {
            throw new IllegalArgumentException("moduleCount must match modules size");
        }
        nature = Objects.requireNonNull(nature, "nature");
        if (nature == InformationNature.FACTUAL) {
            throw new IllegalArgumentException("architecture concentration report must be derived");
        }
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.isEmpty()) {
            throw new IllegalArgumentException("architecture concentration report requires evidence");
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
