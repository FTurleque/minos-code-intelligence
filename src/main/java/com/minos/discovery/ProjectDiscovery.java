package com.minos.discovery;

import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Résultat factuel et immuable de la découverte d'un projet local.
 *
 * <p>Ce contrat ne crée volontairement aucun identifiant métier de projet :
 * l'identité persistante sera portée par le registre M1 et ne doit pas être
 * déduite du seul chemin local.</p>
 */
public record ProjectDiscovery(
        Path rootPath,
        String name,
        Set<Language> languages,
        Set<BuildSystem> buildSystems,
        List<DiscoveredModule> modules
) {

    public ProjectDiscovery {
        Objects.requireNonNull(rootPath, "rootPath");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        languages = immutableEnumSet(languages, Language.class);
        buildSystems = immutableEnumSet(buildSystems, BuildSystem.class);
        modules = List.copyOf(Objects.requireNonNull(modules, "modules"));
    }

    private static <E extends Enum<E>> Set<E> immutableEnumSet(Set<E> values, Class<E> enumType) {
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) {
            return Set.of();
        }
        EnumSet<E> copy = EnumSet.copyOf(values);
        return Collections.unmodifiableSet(copy);
    }

    public enum Language {
        JAVA,
        TYPESCRIPT
    }

    public enum BuildSystem {
        MAVEN,
        NPM
    }

    public enum SourceRootKind {
        SOURCE,
        TEST
    }

    public record SourceRoot(Path relativePath, SourceRootKind kind, Language language) {
        public SourceRoot {
            relativePath = normalizeRelativePath(relativePath, "relativePath");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(language, "language");
        }
    }

    public record DiscoveredModule(
            Path relativePath,
            String name,
            Set<BuildSystem> buildSystems,
            List<SourceRoot> sourceRoots
    ) {
        public DiscoveredModule {
            relativePath = normalizeRelativePath(relativePath, "relativePath");
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            buildSystems = immutableEnumSet(buildSystems, BuildSystem.class);
            sourceRoots = List.copyOf(Objects.requireNonNull(sourceRoots, "sourceRoots"));
        }
    }

    private static Path normalizeRelativePath(Path path, String label) {
        Objects.requireNonNull(path, label);
        if (path.isAbsolute()) {
            throw new IllegalArgumentException(label + " must be relative");
        }
        Path normalized = path.normalize();
        if (normalized.startsWith("..")) {
            throw new IllegalArgumentException(label + " must stay inside the project root");
        }
        return normalized;
    }
}
