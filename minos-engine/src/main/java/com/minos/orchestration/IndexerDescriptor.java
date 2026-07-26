package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.Language;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Description fournisseur-indépendante d'un indexeur enregistrable dans MINOS.
 */
public record IndexerDescriptor(
        String id,
        String version,
        String displayName,
        Set<Language> languages,
        Set<BuildSystem> buildSystems,
        Set<IndexerCapability> capabilities,
        IndexerQualification qualification,
        int priority,
        List<String> limitations
) {

    public IndexerDescriptor {
        id = requireText(id, "id");
        version = requireText(version, "version");
        displayName = requireText(displayName, "displayName");
        languages = immutableEnumSet(languages, Language.class, "languages");
        if (languages.isEmpty()) {
            throw new IllegalArgumentException("languages must not be empty");
        }
        buildSystems = immutableEnumSet(buildSystems, BuildSystem.class, "buildSystems");
        capabilities = immutableEnumSet(capabilities, IndexerCapability.class, "capabilities");
        Objects.requireNonNull(qualification, "qualification");
        limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations"));
        if (limitations.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("limitations must not contain blank values");
        }
    }

    public boolean supports(Language language) {
        return languages.contains(Objects.requireNonNull(language, "language"));
    }

    public boolean acceptsBuildSystems(Set<BuildSystem> detectedBuildSystems) {
        Objects.requireNonNull(detectedBuildSystems, "detectedBuildSystems");
        return buildSystems.isEmpty()
                || detectedBuildSystems.stream().anyMatch(buildSystems::contains);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    private static <E extends Enum<E>> Set<E> immutableEnumSet(
            Set<E> values,
            Class<E> enumType,
            String label
    ) {
        Objects.requireNonNull(values, label);
        if (values.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(values));
    }
}
