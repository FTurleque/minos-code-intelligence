package com.minos.architecture;

import com.minos.domain.Evidence;
import com.minos.domain.InformationNature;

import java.util.List;
import java.util.Objects;

/**
 * Vue métier composée de l'intelligence d'architecture d'un snapshot MINOS.
 *
 * <p>Elle ne recalcule aucun signal : elle garantit que les vues M6 composées
 * appartiennent au même projet et au même snapshot.</p>
 */
public record ArchitectureIntelligenceView(
        String projectId,
        String projectName,
        String snapshotId,
        ArchitectureOverview overview,
        ArchitectureDependencyGraph dependencies,
        ArchitectureConcentrationReport concentration,
        ArchitectureCentralityReport centrality,
        ArchitectureTechnologyReport technologies,
        InformationNature nature,
        List<Evidence> evidence
) {
    public ArchitectureIntelligenceView {
        requireText(projectId, "projectId");
        requireText(projectName, "projectName");
        requireText(snapshotId, "snapshotId");
        overview = Objects.requireNonNull(overview, "overview");
        dependencies = Objects.requireNonNull(dependencies, "dependencies");
        concentration = Objects.requireNonNull(concentration, "concentration");
        centrality = Objects.requireNonNull(centrality, "centrality");
        technologies = Objects.requireNonNull(technologies, "technologies");
        nature = Objects.requireNonNull(nature, "nature");
        if (nature == InformationNature.FACTUAL) {
            throw new IllegalArgumentException("architecture intelligence view must be derived");
        }
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.isEmpty()) {
            throw new IllegalArgumentException("architecture intelligence view requires evidence");
        }

        requireSameProjectAndSnapshot(projectId, snapshotId, overview.projectId(), overview.snapshotId(), "overview");
        requireSameProjectAndSnapshot(projectId, snapshotId, dependencies.projectId(), dependencies.snapshotId(), "dependencies");
        requireSameProjectAndSnapshot(projectId, snapshotId, concentration.projectId(), concentration.snapshotId(), "concentration");
        requireSameProjectAndSnapshot(projectId, snapshotId, centrality.projectId(), centrality.snapshotId(), "centrality");
        requireSameProjectAndSnapshot(projectId, snapshotId, technologies.projectId(), technologies.snapshotId(), "technologies");

        if (!projectName.equals(overview.projectName())) {
            throw new IllegalArgumentException("projectName must match architecture overview");
        }
        if (overview.moduleCount() != concentration.moduleCount()) {
            throw new IllegalArgumentException("concentration module count must match overview");
        }
        if (overview.moduleCount() != centrality.moduleCount()) {
            throw new IllegalArgumentException("centrality module count must match overview");
        }
    }

    private static void requireSameProjectAndSnapshot(
            String projectId,
            String snapshotId,
            String candidateProjectId,
            String candidateSnapshotId,
            String label
    ) {
        if (!projectId.equals(candidateProjectId)) {
            throw new IllegalArgumentException(label + " projectId must match composed view");
        }
        if (!snapshotId.equals(candidateSnapshotId)) {
            throw new IllegalArgumentException(label + " snapshotId must match composed view");
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}