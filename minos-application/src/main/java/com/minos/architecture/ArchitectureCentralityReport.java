package com.minos.architecture;

import com.minos.domain.Evidence;
import com.minos.domain.InformationNature;

import java.util.List;
import java.util.Objects;

/**
 * Rapport de classement relatif des modules, séparant centralité entrante et sortante.
 */
public record ArchitectureCentralityReport(
        String projectId,
        String snapshotId,
        int moduleCount,
        int rankedIncomingModuleCount,
        int rankedOutgoingModuleCount,
        List<String> topIncomingModuleIds,
        List<String> topOutgoingModuleIds,
        List<ArchitectureModuleCentrality> modules,
        InformationNature nature,
        List<Evidence> evidence
) {
    public ArchitectureCentralityReport {
        requireText(projectId, "projectId");
        requireText(snapshotId, "snapshotId");
        requireNonNegative(moduleCount, "moduleCount");
        requireNonNegative(rankedIncomingModuleCount, "rankedIncomingModuleCount");
        requireNonNegative(rankedOutgoingModuleCount, "rankedOutgoingModuleCount");
        topIncomingModuleIds = List.copyOf(Objects.requireNonNull(topIncomingModuleIds, "topIncomingModuleIds"));
        topOutgoingModuleIds = List.copyOf(Objects.requireNonNull(topOutgoingModuleIds, "topOutgoingModuleIds"));
        modules = List.copyOf(Objects.requireNonNull(modules, "modules"));
        if (modules.size() != moduleCount) {
            throw new IllegalArgumentException("moduleCount must match modules size");
        }
        long actualIncomingRanked = modules.stream().filter(module -> module.incomingRank() > 0).count();
        long actualOutgoingRanked = modules.stream().filter(module -> module.outgoingRank() > 0).count();
        if (actualIncomingRanked != rankedIncomingModuleCount) {
            throw new IllegalArgumentException("rankedIncomingModuleCount must match ranked modules");
        }
        if (actualOutgoingRanked != rankedOutgoingModuleCount) {
            throw new IllegalArgumentException("rankedOutgoingModuleCount must match ranked modules");
        }
        List<String> expectedIncomingTop = modules.stream()
                .filter(module -> module.incomingRank() == 1)
                .map(ArchitectureModuleCentrality::moduleId)
                .toList();
        List<String> expectedOutgoingTop = modules.stream()
                .filter(module -> module.outgoingRank() == 1)
                .map(ArchitectureModuleCentrality::moduleId)
                .toList();
        if (!expectedIncomingTop.equals(topIncomingModuleIds)) {
            throw new IllegalArgumentException("topIncomingModuleIds must match rank 1 modules");
        }
        if (!expectedOutgoingTop.equals(topOutgoingModuleIds)) {
            throw new IllegalArgumentException("topOutgoingModuleIds must match rank 1 modules");
        }
        nature = Objects.requireNonNull(nature, "nature");
        if (nature == InformationNature.FACTUAL) {
            throw new IllegalArgumentException("architecture centrality report must be derived");
        }
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.isEmpty()) {
            throw new IllegalArgumentException("architecture centrality report requires evidence");
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
}
