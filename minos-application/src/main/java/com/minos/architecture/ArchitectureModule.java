package com.minos.architecture;

import com.minos.domain.Evidence;
import com.minos.domain.InformationNature;

import java.util.List;
import java.util.Objects;

/**
 * Module factuel issu de la découverte, enrichi d'agrégats de connaissance.
 */
public record ArchitectureModule(
        String id,
        String name,
        String relativePath,
        List<String> buildSystems,
        List<String> languages,
        int sourceRootCount,
        int symbolCount,
        List<ArchitectureNamespace> namespaces,
        InformationNature nature,
        InformationNature aggregateNature,
        List<Evidence> evidence
) {

    public ArchitectureModule {
        requireText(id, "id");
        requireText(name, "name");
        relativePath = relativePath == null ? "" : relativePath;
        buildSystems = List.copyOf(Objects.requireNonNull(buildSystems, "buildSystems"));
        languages = List.copyOf(Objects.requireNonNull(languages, "languages"));
        if (sourceRootCount < 0) {
            throw new IllegalArgumentException("sourceRootCount must not be negative");
        }
        if (symbolCount < 0) {
            throw new IllegalArgumentException("symbolCount must not be negative");
        }
        namespaces = List.copyOf(Objects.requireNonNull(namespaces, "namespaces"));
        nature = Objects.requireNonNull(nature, "nature");
        aggregateNature = Objects.requireNonNull(aggregateNature, "aggregateNature");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (aggregateNature != InformationNature.FACTUAL && evidence.isEmpty()) {
            throw new IllegalArgumentException("derived module aggregates require evidence");
        }
    }

    public ArchitectureModule(
            String id,
            String name,
            String relativePath,
            List<String> buildSystems,
            List<String> languages,
            int sourceRootCount,
            int symbolCount,
            List<ArchitectureNamespace> namespaces,
            InformationNature nature,
            List<Evidence> evidence
    ) {
        this(
                id,
                name,
                relativePath,
                buildSystems,
                languages,
                sourceRootCount,
                symbolCount,
                namespaces,
                nature,
                InformationNature.DERIVED,
                evidence
        );
    }

    public int namespaceCount() {
        return namespaces.size();
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
