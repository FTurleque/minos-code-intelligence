package com.minos.architecture;

import com.minos.domain.Evidence;
import com.minos.domain.InformationNature;

import java.util.List;
import java.util.Objects;

/**
 * Contexte architectural compact d'un module pour un snapshot donné.
 */
public record ArchitectureModuleContext(
        String projectId,
        String snapshotId,
        ArchitectureModule module,
        List<ArchitectureModuleDependency> incomingDependencies,
        List<ArchitectureModuleDependency> outgoingDependencies,
        ArchitectureModuleConcentration concentration,
        ArchitectureModuleCentrality centrality,
        List<ArchitectureTechnology> technologies,
        InformationNature nature,
        List<Evidence> evidence
) {
    public ArchitectureModuleContext {
        requireText(projectId, "projectId");
        requireText(snapshotId, "snapshotId");
        module = Objects.requireNonNull(module, "module");
        incomingDependencies = List.copyOf(Objects.requireNonNull(incomingDependencies, "incomingDependencies"));
        outgoingDependencies = List.copyOf(Objects.requireNonNull(outgoingDependencies, "outgoingDependencies"));
        concentration = Objects.requireNonNull(concentration, "concentration");
        centrality = Objects.requireNonNull(centrality, "centrality");
        technologies = List.copyOf(Objects.requireNonNull(technologies, "technologies"));
        nature = Objects.requireNonNull(nature, "nature");
        if (nature == InformationNature.FACTUAL) {
            throw new IllegalArgumentException("architecture module context must be derived");
        }
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.isEmpty()) {
            throw new IllegalArgumentException("architecture module context requires evidence");
        }

        String moduleId = module.id();
        if (!moduleId.equals(concentration.moduleId())) {
            throw new IllegalArgumentException("concentration must belong to context module");
        }
        if (!moduleId.equals(centrality.moduleId())) {
            throw new IllegalArgumentException("centrality must belong to context module");
        }
        if (incomingDependencies.stream().anyMatch(edge -> !moduleId.equals(edge.targetModuleId()))) {
            throw new IllegalArgumentException("incoming dependency targets must match context module");
        }
        if (outgoingDependencies.stream().anyMatch(edge -> !moduleId.equals(edge.sourceModuleId()))) {
            throw new IllegalArgumentException("outgoing dependency sources must match context module");
        }
        if (technologies.stream().anyMatch(technology -> !technology.moduleIds().contains(moduleId))) {
            throw new IllegalArgumentException("technologies must be observed on context module");
        }
    }

    public int incomingModuleEdgeCount() {
        return incomingDependencies.size();
    }

    public int outgoingModuleEdgeCount() {
        return outgoingDependencies.size();
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}