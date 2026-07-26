package com.minos.architecture;

import com.minos.domain.Evidence;
import com.minos.domain.InformationNature;

import java.util.List;
import java.util.Objects;

/**
 * Technologie observée factuellement dans la découverte d'un projet.
 */
public record ArchitectureTechnology(
        String id,
        String name,
        ArchitectureTechnologyCategory category,
        List<String> moduleIds,
        InformationNature nature,
        List<Evidence> evidence
) {
    public ArchitectureTechnology {
        requireText(id, "id");
        requireText(name, "name");
        category = Objects.requireNonNull(category, "category");
        moduleIds = List.copyOf(Objects.requireNonNull(moduleIds, "moduleIds"));
        nature = Objects.requireNonNull(nature, "nature");
        if (nature != InformationNature.FACTUAL) {
            throw new IllegalArgumentException("detected technology must be factual");
        }
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.isEmpty()) {
            throw new IllegalArgumentException("detected technology requires evidence");
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
