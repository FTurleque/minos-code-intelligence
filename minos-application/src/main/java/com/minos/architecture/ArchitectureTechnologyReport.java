package com.minos.architecture;

import com.minos.domain.Evidence;
import com.minos.domain.InformationNature;

import java.util.List;
import java.util.Objects;

/**
 * Agrégation déterministe des technologies observées pour un projet et son
 * snapshot d'architecture courant.
 */
public record ArchitectureTechnologyReport(
        String projectId,
        String snapshotId,
        int technologyCount,
        List<ArchitectureTechnology> technologies,
        InformationNature nature,
        List<Evidence> evidence
) {
    public ArchitectureTechnologyReport {
        requireText(projectId, "projectId");
        requireText(snapshotId, "snapshotId");
        if (technologyCount < 0) {
            throw new IllegalArgumentException("technologyCount must not be negative");
        }
        technologies = List.copyOf(Objects.requireNonNull(technologies, "technologies"));
        if (technologyCount != technologies.size()) {
            throw new IllegalArgumentException("technologyCount must match technologies size");
        }
        nature = Objects.requireNonNull(nature, "nature");
        if (nature == InformationNature.FACTUAL) {
            throw new IllegalArgumentException("technology report must be derived");
        }
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.isEmpty()) {
            throw new IllegalArgumentException("technology report requires evidence");
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
