package com.minos.architecture;

import com.minos.domain.Evidence;
import com.minos.domain.InformationNature;

import java.util.List;
import java.util.Objects;

/**
 * Namespace ou package observé dans un module de la topologie MINOS.
 *
 * <p>Le nom logique est dérivé des racines source et des chemins de fichiers,
 * sans prétendre reconstruire une sémantique de langage absente des faits.</p>
 */
public record ArchitectureNamespace(
        String id,
        String name,
        String relativePath,
        int symbolCount,
        List<String> languages,
        InformationNature nature,
        List<Evidence> evidence
) {

    public ArchitectureNamespace {
        requireText(id, "id");
        requireText(name, "name");
        relativePath = relativePath == null ? "" : relativePath;
        if (symbolCount < 0) {
            throw new IllegalArgumentException("symbolCount must not be negative");
        }
        languages = List.copyOf(Objects.requireNonNull(languages, "languages"));
        nature = Objects.requireNonNull(nature, "nature");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (nature != InformationNature.FACTUAL && evidence.isEmpty()) {
            throw new IllegalArgumentException("derived architecture namespace requires evidence");
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
